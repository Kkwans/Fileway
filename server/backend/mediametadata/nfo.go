// Package mediametadata parses local media metadata without accessing media,
// the network, or user state. Parsed values are evidence, not match decisions.
package mediametadata

import (
	"bytes"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"math"
	"strings"
)

const (
	DefaultMaxBytes    int64 = 2 << 20
	DefaultMaxDepth          = 32
	DefaultMaxElements       = 20000
)

var (
	ErrTooLarge        = errors.New("NFO exceeds byte limit")
	ErrTooDeep         = errors.New("NFO exceeds XML depth limit")
	ErrTooManyElements = errors.New("NFO exceeds XML element limit")
	ErrDirective       = errors.New("NFO XML directives are not allowed")
	ErrMalformed       = errors.New("malformed NFO XML")
	ErrUnsupportedRoot = errors.New("unsupported NFO root")
	ErrMultipleRoots   = errors.New("only episode NFOs may contain multiple roots")
	ErrInvalidLimits   = errors.New("invalid NFO limits")
)

// Limits bounds untrusted input. Zero values select the defaults. MaxDepth
// includes the document root. Negative values are invalid.
type Limits struct {
	MaxBytes    int64
	MaxDepth    int
	MaxElements int
}

type Kind string

const (
	Movie   Kind = "movie"
	TVShow  Kind = "tvshow"
	Season  Kind = "season"
	Episode Kind = "episodedetails"
)

type Document struct {
	Items []Item `json:"items"`
}

// Item contains descriptive input only. A missing Fields key means absent;
// a present empty element is []string{""}. Repeated fields retain their order.
// Values (including year/season/episode) remain text until domain validation.
// watched, playcount, resume, lastplayed and userrating are never imported.
type Item struct {
	Kind        Kind                `json:"kind"`
	Fields      map[string][]string `json:"fields"`
	ExternalIDs []ExternalID        `json:"externalIds,omitempty"`
	Ratings     []Rating            `json:"ratings,omitempty"`
}

// ExternalID retains conflicting and repeated identities for the caller to
// resolve. An unqualified legacy <id> has no inferred provider.
type ExternalID struct {
	Provider string `json:"provider"`
	Value    string `json:"value"`
	Default  *bool  `json:"default,omitempty"`
	Tag      string `json:"tag"`
}

// ParseNFO accepts one movie, tvshow, season or episodedetails root. Legacy
// multi-episode NFOs may contain consecutive episodedetails roots. It rejects
// directives (including DTDs), unknown entities and malformed or oversized XML.
// It performs no identity matching, permission checks, file writes or requests.
func ParseNFO(r io.Reader, limits Limits) (*Document, error) {
	if limits.MaxBytes < 0 || limits.MaxBytes == math.MaxInt64 || limits.MaxDepth < 0 || limits.MaxElements < 0 {
		return nil, ErrInvalidLimits
	}
	if limits.MaxBytes == 0 {
		limits.MaxBytes = DefaultMaxBytes
	}
	if limits.MaxDepth == 0 {
		limits.MaxDepth = DefaultMaxDepth
	}
	if limits.MaxElements == 0 {
		limits.MaxElements = DefaultMaxElements
	}
	data, err := io.ReadAll(io.LimitReader(r, limits.MaxBytes+1))
	if err != nil {
		return nil, fmt.Errorf("read NFO: %w", err)
	}
	if int64(len(data)) > limits.MaxBytes {
		return nil, ErrTooLarge
	}
	data = bytes.TrimPrefix(data, []byte{0xef, 0xbb, 0xbf})
	decoder := xml.NewDecoder(bytes.NewReader(data))
	var stack []*element
	var roots []*element
	declarationSeen := false
	elements := 0
	for {
		token, err := decoder.Token()
		if err == io.EOF {
			break
		}
		if err != nil {
			// Do not echo arbitrary source text (or entity names) in errors.
			return nil, fmt.Errorf("%w at byte %d", ErrMalformed, decoder.InputOffset())
		}
		switch token := token.(type) {
		case xml.Directive:
			return nil, ErrDirective
		case xml.ProcInst:
			if token.Target != "xml" || declarationSeen || len(roots) != 0 || len(stack) != 0 {
				return nil, ErrDirective
			}
			declarationSeen = true
		case xml.StartElement:
			elements++
			if elements > limits.MaxElements {
				return nil, ErrTooManyElements
			}
			if len(stack)+1 > limits.MaxDepth {
				return nil, ErrTooDeep
			}
			node := &element{name: token.Name, attrs: make(map[string]string)}
			seen := make(map[xml.Name]bool, len(token.Attr))
			for _, attr := range token.Attr {
				if seen[attr.Name] {
					return nil, ErrMalformed
				}
				seen[attr.Name] = true
				if attr.Name.Space == "" {
					node.attrs[attr.Name.Local] = attr.Value
				}
			}
			if len(stack) == 0 {
				if token.Name.Space != "" || !supportedKind(Kind(token.Name.Local)) {
					return nil, ErrUnsupportedRoot
				}
				roots = append(roots, node)
			} else {
				parent := stack[len(stack)-1]
				parent.parts = append(parent.parts, part{child: node})
			}
			stack = append(stack, node)
		case xml.EndElement:
			stack = stack[:len(stack)-1]
		case xml.CharData:
			if len(stack) == 0 {
				if strings.TrimSpace(string(token)) != "" {
					return nil, ErrMalformed
				}
			} else {
				node := stack[len(stack)-1]
				node.parts = append(node.parts, part{text: string(token)})
			}
		}
	}
	if len(roots) == 0 || len(stack) != 0 {
		return nil, ErrMalformed
	}
	if len(roots) > 1 {
		for _, root := range roots {
			if Kind(root.name.Local) != Episode {
				return nil, ErrMultipleRoots
			}
		}
	}
	document := &Document{Items: make([]Item, 0, len(roots))}
	for _, root := range roots {
		document.Items = append(document.Items, parseItem(root))
	}
	return document, nil
}

func supportedKind(kind Kind) bool {
	return kind == Movie || kind == TVShow || kind == Season || kind == Episode
}

type part struct {
	text  string
	child *element
}

type element struct {
	name  xml.Name
	attrs map[string]string
	parts []part
}

func (e *element) text() string {
	var out strings.Builder
	var appendText func(*element)
	appendText = func(node *element) {
		for _, part := range node.parts {
			if part.child != nil {
				appendText(part.child)
			} else {
				out.WriteString(part.text)
			}
		}
	}
	appendText(e)
	return strings.TrimSpace(out.String())
}

func (e *element) children() []*element {
	var children []*element
	for _, part := range e.parts {
		if part.child != nil && part.child.name.Space == "" {
			children = append(children, part.child)
		}
	}
	return children
}

func parseItem(root *element) Item {
	item := Item{Kind: Kind(root.name.Local), Fields: make(map[string][]string)}
	for _, child := range root.children() {
		tag := child.name.Local
		switch tag {
		case "uniqueid", "id", "imdbid", "imdb_id", "tmdbid", "tvdbid", "doubanid":
			provider := strings.ToLower(strings.TrimSpace(child.attrs["type"]))
			switch tag {
			case "imdbid", "imdb_id":
				provider = "imdb"
			case "tmdbid":
				provider = "tmdb"
			case "tvdbid":
				provider = "tvdb"
			case "doubanid":
				provider = "douban"
			}
			item.ExternalIDs = append(item.ExternalIDs, ExternalID{
				Provider: provider, Value: child.text(), Default: parseBoolean(child.attrs["default"]), Tag: tag,
			})
		case "ratings":
			for _, rating := range child.children() {
				if rating.name.Local == "rating" {
					item.Ratings = append(item.Ratings, parseRating(rating))
				}
			}
		case "rating":
			// Legacy unqualified ratings have no safe provider/metric inference.
			item.Ratings = append(item.Ratings, parseLegacyRating(child))
		default:
			if descriptiveField(tag) {
				item.Fields[tag] = append(item.Fields[tag], child.text())
			}
		}
	}
	return item
}

func descriptiveField(tag string) bool {
	switch tag {
	case "title", "originaltitle", "sorttitle", "plot", "outline", "tagline",
		"year", "premiered", "aired", "releasedate", "season", "episode",
		"seasonnumber", "showtitle", "displayseason", "displayepisode",
		"runtime", "mpaa", "country", "genre", "tag", "studio", "director",
		"credits", "writer", "status":
		return true
	default:
		return false
	}
}

func parseBoolean(value string) *bool {
	switch strings.ToLower(strings.TrimSpace(value)) {
	case "true", "1":
		value := true
		return &value
	case "false", "0":
		value := false
		return &value
	default:
		return nil
	}
}
