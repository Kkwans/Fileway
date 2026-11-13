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
	Kind        Kind                    `json:"kind"`
	Fields      map[string][]string     `json:"fields"`
	ExternalIDs []ExternalID            `json:"externalIds,omitempty"`
	Ratings     []Rating                `json:"ratings,omitempty"`
	Credits     []Credit                `json:"credits,omitempty"`
	Artwork     []ArtworkDeclaration    `json:"artwork,omitempty"`
	Collections []CollectionDeclaration `json:"collections,omitempty"`
}

// Credit is an unverified local credit statement, not a resolved person. Kind
// is actor, director or writer; Tag retains distinctions such as credits/writer.
// Fields retain field presence, duplicate names/roles and raw order values.
// Input order is retained, without sorting or guessing identities from names.
type Credit struct {
	Kind        string               `json:"kind"`
	Tag         string               `json:"tag"`
	Attributes  map[string]string    `json:"attributes,omitempty"`
	Fields      map[string][]string  `json:"fields"`
	ExternalIDs []ExternalID         `json:"externalIds,omitempty"`
	Artwork     []ArtworkDeclaration `json:"artwork,omitempty"`
}

// ArtworkDeclaration is only source text. Reference and attributes such as
// preview may be local paths, URLs, invalid or empty strings. Nothing here is
// resolved, fetched, validated for access or safe to pass to an image loader.
// A downstream source resolver must authorize every reference independently.
// Role is empty for absent/unknown semantics; Tag, Container and Attributes
// preserve the original declaration, including unknown aspect values.
type ArtworkDeclaration struct {
	Tag                 string            `json:"tag"`
	Container           string            `json:"container,omitempty"`
	Role                string            `json:"role,omitempty"`
	Reference           string            `json:"reference"`
	Attributes          map[string]string `json:"attributes,omitempty"`
	ContainerAttributes map[string]string `json:"containerAttributes,omitempty"`
}

// CollectionDeclaration retains a set/collection statement without creating
// relationships or merging equal names. A separate item collectionnumber is
// retained in Item.Fields and is not assigned to an arbitrary collection.
type CollectionDeclaration struct {
	Tag         string               `json:"tag"`
	Attributes  map[string]string    `json:"attributes,omitempty"`
	Fields      map[string][]string  `json:"fields"`
	ExternalIDs []ExternalID         `json:"externalIds,omitempty"`
	Artwork     []ArtworkDeclaration `json:"artwork,omitempty"`
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

func (e *element) hasElementChildren() bool {
	for _, part := range e.parts {
		if part.child != nil {
			return true
		}
	}
	return false
}

func parseItem(root *element) Item {
	item := Item{Kind: Kind(root.name.Local), Fields: make(map[string][]string)}
	for _, child := range root.children() {
		tag := child.name.Local
		switch tag {
		case "uniqueid", "id", "imdbid", "imdb_id", "tmdbid", "tvdbid", "doubanid":
			item.ExternalIDs = append(item.ExternalIDs, parseExternalID(child))
		case "actor", "director", "writer", "credits":
			item.Credits = append(item.Credits, parseCredit(child))
			if tag != "actor" && !child.hasElementChildren() {
				// Keep the existing scalar field contract for ordinary names.
				item.Fields[tag] = append(item.Fields[tag], child.text())
			}
		case "thumb", "fanart", "art":
			item.Artwork = append(item.Artwork, parseArtwork(child, "")...)
		case "set", "collection":
			item.Collections = append(item.Collections, parseCollection(child))
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
		"credits", "writer", "status", "collectionnumber":
		return true
	default:
		return false
	}
}

func parseExternalID(node *element) ExternalID {
	provider := strings.ToLower(strings.TrimSpace(node.attrs["type"]))
	switch node.name.Local {
	case "imdbid", "imdb_id":
		provider = "imdb"
	case "tmdbid":
		provider = "tmdb"
	case "tvdbid":
		provider = "tvdb"
	case "doubanid":
		provider = "douban"
	}
	return ExternalID{Provider: provider, Value: node.text(), Default: parseBoolean(node.attrs["default"]), Tag: node.name.Local}
}

func parseCredit(node *element) Credit {
	credit := Credit{Kind: node.name.Local, Tag: node.name.Local, Attributes: node.attrs, Fields: make(map[string][]string)}
	if credit.Kind == "credits" {
		credit.Kind = "writer"
	}
	if !node.hasElementChildren() {
		// Empty actors do not declare a name; empty scalar crew fields do.
		if node.name.Local != "actor" || node.text() != "" {
			credit.Fields["name"] = []string{node.text()}
		}
		return credit
	}
	parseNamedDeclaration(node, credit.Fields, &credit.ExternalIDs, &credit.Artwork)
	return credit
}

func parseCollection(node *element) CollectionDeclaration {
	collection := CollectionDeclaration{Tag: node.name.Local, Attributes: node.attrs, Fields: make(map[string][]string)}
	if !node.hasElementChildren() {
		collection.Fields["name"] = []string{node.text()}
		return collection
	}
	parseNamedDeclaration(node, collection.Fields, &collection.ExternalIDs, &collection.Artwork)
	return collection
}

// Preserve unknown scalar extensions as evidence; do not reinterpret their
// names as provider IDs. User state remains excluded at these nesting levels.
func parseNamedDeclaration(node *element, fields map[string][]string, ids *[]ExternalID, artwork *[]ArtworkDeclaration) {
	for _, child := range node.children() {
		switch child.name.Local {
		case "uniqueid", "id", "imdbid", "imdb_id", "tmdbid", "tvdbid", "doubanid":
			*ids = append(*ids, parseExternalID(child))
		case "thumb", "fanart", "art":
			*artwork = append(*artwork, parseArtwork(child, node.name.Local)...)
		case "watched", "playcount", "resume", "lastplayed", "userrating":
			continue
		default:
			if !child.hasElementChildren() {
				fields[child.name.Local] = append(fields[child.name.Local], child.text())
			}
		}
	}
}

func parseArtwork(node *element, container string) []ArtworkDeclaration {
	children := node.children()
	if node.name.Local == "art" {
		if !node.hasElementChildren() {
			return []ArtworkDeclaration{artworkDeclaration(node, container)}
		}
		var artwork []ArtworkDeclaration
		for _, child := range children {
			switch child.name.Local {
			case "watched", "playcount", "resume", "lastplayed", "userrating":
				continue
			}
			declarations := parseArtwork(child, "art")
			for index := range declarations {
				if declarations[index].Container == "art" {
					declarations[index].ContainerAttributes = node.attrs
				}
			}
			artwork = append(artwork, declarations...)
		}
		return artwork
	}
	if node.name.Local == "fanart" && node.hasElementChildren() {
		var artwork []ArtworkDeclaration
		for _, child := range children {
			if child.name.Local == "thumb" && !child.hasElementChildren() {
				declaration := artworkDeclaration(child, "fanart")
				declaration.ContainerAttributes = node.attrs
				artwork = append(artwork, declaration)
			}
		}
		return artwork
	}
	if node.hasElementChildren() {
		// Structured extensions are not executable references or URLs.
		return nil
	}
	return []ArtworkDeclaration{artworkDeclaration(node, container)}
}

func artworkDeclaration(node *element, container string) ArtworkDeclaration {
	declaration := ArtworkDeclaration{Tag: node.name.Local, Container: container, Reference: node.text(), Attributes: node.attrs}
	if aspect, exists := node.attrs["aspect"]; exists {
		declaration.Role = artworkRole(aspect)
	} else if node.name.Local != "thumb" {
		declaration.Role = artworkRole(node.name.Local)
	} else if container == "fanart" {
		declaration.Role = "backdrop"
	} else if container == "actor" || container == "director" || container == "writer" || container == "credits" {
		declaration.Role = "person"
	}
	return declaration
}

func artworkRole(raw string) string {
	switch strings.ToLower(strings.TrimSpace(raw)) {
	case "poster", "banner", "landscape", "thumb", "discart", "clearart", "keyart":
		return strings.ToLower(strings.TrimSpace(raw))
	case "fanart", "backdrop":
		return "backdrop"
	case "clearlogo", "logo":
		return "logo"
	default:
		return ""
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
