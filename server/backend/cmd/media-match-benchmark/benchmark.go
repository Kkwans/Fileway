package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"sort"
	"strings"
	"time"
	"unicode"
)

const maxBenchmarkManifestLine = 64 * 1024

type benchmarkExpected struct {
	State      string `json:"state"`
	Provider   string `json:"provider,omitempty"`
	Kind       string `json:"kind,omitempty"`
	ExternalID string `json:"externalId,omitempty"`
}

type benchmarkSample struct {
	ID         string            `json:"id"`
	LibraryID  string            `json:"libraryId"`
	ItemID     string            `json:"itemId"`
	SourceRoot string            `json:"sourceRoot,omitempty"`
	Corpus     string            `json:"corpus"`
	Category   string            `json:"category"`
	Rationale  string            `json:"rationale"`
	Expected   benchmarkExpected `json:"expected"`
}

type benchmarkCandidate struct {
	Provider      string   `json:"provider"`
	Kind          string   `json:"kind"`
	ExternalID    string   `json:"externalId"`
	Title         string   `json:"title,omitempty"`
	OriginalTitle string   `json:"originalTitle,omitempty"`
	Year          int      `json:"year,omitempty"`
	Evidence      []string `json:"evidence,omitempty"`
}

type benchmarkActual struct {
	State              string               `json:"state"`
	Reason             string               `json:"reason,omitempty"`
	Provider           string               `json:"provider,omitempty"`
	Kind               string               `json:"kind,omitempty"`
	ExternalID         string               `json:"externalId,omitempty"`
	Title              string               `json:"title,omitempty"`
	Year               string               `json:"year,omitempty"`
	Language           string               `json:"language,omitempty"`
	Region             string               `json:"region,omitempty"`
	Evidence           []string             `json:"evidence,omitempty"`
	Candidates         []benchmarkCandidate `json:"candidates,omitempty"`
	InputTitle         string               `json:"inputTitle,omitempty"`
	InputYear          int                  `json:"inputYear,omitempty"`
	Aliases            []string             `json:"aliases,omitempty"`
	DurationMs         int64                `json:"durationMs,omitempty"`
	Incomplete         bool                 `json:"incomplete"`
	Ambiguities        []string             `json:"ambiguities,omitempty"`
	ItemRevision       int64                `json:"itemRevision"`
	StructureRevision  int64                `json:"structureRevision"`
	PolicyRevision     int64                `json:"policyRevision"`
	LocalRulesRevision int64                `json:"localRulesRevision"`
	AutoTitleMatch     bool                 `json:"autoTitleMatch"`
	AdapterVersion     string               `json:"adapterVersion,omitempty"`
}

type benchmarkResult struct {
	ID               string            `json:"id"`
	LibraryID        string            `json:"libraryId"`
	ItemID           string            `json:"itemId"`
	SourceRoot       string            `json:"sourceRoot,omitempty"`
	Corpus           string            `json:"corpus"`
	Category         string            `json:"category"`
	Rationale        string            `json:"rationale"`
	Expected         benchmarkExpected `json:"expected"`
	Actual           benchmarkActual   `json:"actual"`
	Filename         string            `json:"filename,omitempty"`
	StateMatch       bool              `json:"stateMatch"`
	IdentityMatch    bool              `json:"identityMatch"`
	AutomaticCorrect bool              `json:"automaticCorrect"`
	WrongAutomatic   bool              `json:"wrongAutomatic"`
	Missed           bool              `json:"missed"`
	CandidateRank    int               `json:"candidateRank"`
	RecallAt1        bool              `json:"recallAt1"`
	RecallAt3        bool              `json:"recallAt3"`
	RecallAt5        bool              `json:"recallAt5"`
	RecallAt10       bool              `json:"recallAt10"`
	ReciprocalRank   float64           `json:"reciprocalRank"`
	Requests         int               `json:"requests"`
	ElapsedMs        int64             `json:"elapsedMs"`
	Error            string            `json:"error,omitempty"`
}

type benchmarkGate struct {
	MinSamples       int      `json:"minSamples"`
	MinRealSamples   int      `json:"minRealSamples"`
	RequiredRoots    []string `json:"requiredRoots,omitempty"`
	MinPerRoot       int      `json:"minPerRoot"`
	MinAutoCoverage  float64  `json:"minAutoCoverage"`
	MinStateAccuracy float64  `json:"minStateAccuracy"`
	RecallK          int      `json:"recallK"`
	MinRecall        float64  `json:"minRecall"`
	MinMRR           float64  `json:"minMRR"`
}

type benchmarkSummary struct {
	SourceRevision    string         `json:"sourceRevision"`
	GeneratedAt       time.Time      `json:"generatedAt"`
	Gate              benchmarkGate  `json:"gate"`
	TotalSamples      int            `json:"totalSamples"`
	RealNASSamples    int            `json:"realNasSamples"`
	AuthorizedSamples int            `json:"authorizedSamples"`
	SyntheticSamples  int            `json:"syntheticSamples"`
	ByCorpus          map[string]int `json:"byCorpus"`
	ByCategory        map[string]int `json:"byCategory"`
	ByRoot            map[string]int `json:"byRoot"`
	ByState           map[string]int `json:"byState"`
	ExpectedMatched   int            `json:"expectedMatched"`
	Automatic         int            `json:"automatic"`
	AutomaticCorrect  int            `json:"automaticCorrect"`
	WrongAutomatic    int            `json:"wrongAutomatic"`
	NeedsReview       int            `json:"needsReview"`
	NoMatch           int            `json:"noMatch"`
	Skipped           int            `json:"skipped"`
	Missed            int            `json:"missed"`
	Errors            int            `json:"errors"`
	StateCorrect      int            `json:"stateCorrect"`
	StateAccuracy     float64        `json:"stateAccuracy"`
	AutomaticCoverage float64        `json:"automaticCoverage"`
	RecallAt1         float64        `json:"recallAt1"`
	RecallAt3         float64        `json:"recallAt3"`
	RecallAt5         float64        `json:"recallAt5"`
	RecallAt10        float64        `json:"recallAt10"`
	MRR               float64        `json:"mrr"`
	Requests          int            `json:"requests"`
	ElapsedMs         int64          `json:"elapsedMs"`
	WrongAutomaticIDs []string       `json:"wrongAutomaticIds,omitempty"`
	MissedIDs         []string       `json:"missedIds,omitempty"`
	ErrorIDs          []string       `json:"errorIds,omitempty"`
	Passed            bool           `json:"passed"`
	Failures          []string       `json:"failures,omitempty"`
}

func loadBenchmarkManifest(path string) ([]benchmarkSample, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, fmt.Errorf("open manifest: %w", err)
	}
	defer file.Close()
	scanner := bufio.NewScanner(file)
	scanner.Buffer(make([]byte, 4096), maxBenchmarkManifestLine)
	var samples []benchmarkSample
	seen := make(map[string]struct{})
	line := 0
	for scanner.Scan() {
		line++
		data := bytes.TrimSpace(scanner.Bytes())
		if len(data) == 0 {
			continue
		}
		var sample benchmarkSample
		decoder := json.NewDecoder(bytes.NewReader(data))
		decoder.DisallowUnknownFields()
		if err := decoder.Decode(&sample); err != nil {
			return nil, fmt.Errorf("manifest line %d: %w", line, err)
		}
		if err := validateBenchmarkSample(sample); err != nil {
			return nil, fmt.Errorf("manifest line %d: %w", line, err)
		}
		if _, exists := seen[sample.ID]; exists {
			return nil, fmt.Errorf("manifest line %d: duplicate id %q", line, sample.ID)
		}
		seen[sample.ID] = struct{}{}
		samples = append(samples, sample)
	}
	if err := scanner.Err(); err != nil {
		return nil, fmt.Errorf("read manifest: %w", err)
	}
	if len(samples) == 0 {
		return nil, errors.New("manifest is empty")
	}
	return samples, nil
}

func validateBenchmarkSample(sample benchmarkSample) error {
	if !validBenchmarkID(sample.ID, 160) || !validBenchmarkID(sample.LibraryID, 128) || !validBenchmarkID(sample.ItemID, 128) {
		return errors.New("id, libraryId and itemId must be bounded route identifiers")
	}
	if sample.Corpus != "real_nas" && sample.Corpus != "authorized" && sample.Corpus != "synthetic" {
		return errors.New("corpus must be real_nas, authorized or synthetic")
	}
	if sample.Corpus == "real_nas" && strings.TrimSpace(sample.SourceRoot) == "" {
		return errors.New("real_nas sample requires sourceRoot")
	}
	if strings.TrimSpace(sample.SourceRoot) != sample.SourceRoot || len(sample.SourceRoot) > 1024 || strings.ContainsFunc(sample.SourceRoot, unicode.IsControl) {
		return errors.New("sourceRoot is invalid")
	}
	if strings.TrimSpace(sample.Category) == "" || len(sample.Category) > 80 || strings.ContainsFunc(sample.Category, unicode.IsControl) {
		return errors.New("category is invalid")
	}
	if strings.TrimSpace(sample.Rationale) == "" || len(sample.Rationale) > 1000 || strings.ContainsFunc(sample.Rationale, unicode.IsControl) {
		return errors.New("rationale is required")
	}
	switch sample.Expected.State {
	case "matched":
		if !validBenchmarkID(sample.Expected.Provider, 32) || !validBenchmarkID(sample.Expected.Kind, 32) || !validBenchmarkID(sample.Expected.ExternalID, 64) {
			return errors.New("matched expectation requires provider, kind and externalId")
		}
	case "needs_review", "no_match", "skipped":
		if sample.Expected.Provider != "" || sample.Expected.Kind != "" || sample.Expected.ExternalID != "" {
			return errors.New("non-match expectation cannot declare an identity")
		}
	default:
		return errors.New("expected state must be matched, needs_review, no_match or skipped")
	}
	return nil
}

func validBenchmarkID(value string, limit int) bool {
	return value != "" && value == strings.TrimSpace(value) && len(value) <= limit &&
		!strings.ContainsFunc(value, func(r rune) bool { return unicode.IsControl(r) || r == '/' || r == '\\' })
}

type benchmarkClient struct {
	baseURL     string
	token       string
	http        *http.Client
	maxAttempts int
}

func newBenchmarkClient(baseURL, token string, maxAttempts int) (*benchmarkClient, error) {
	parsed, err := url.Parse(strings.TrimSpace(baseURL))
	if err != nil || parsed.Host == "" || parsed.Scheme != "http" && parsed.Scheme != "https" || parsed.User != nil || parsed.RawQuery != "" || parsed.Fragment != "" {
		return nil, errors.New("base URL must be an HTTP(S) origin without credentials, query or fragment")
	}
	if token == "" || len(token) > 16*1024 || strings.ContainsAny(token, "\r\n") {
		return nil, errors.New("token must be supplied securely without line breaks")
	}
	if maxAttempts < 1 || maxAttempts > 3 {
		return nil, errors.New("max attempts must be between 1 and 3")
	}
	return &benchmarkClient{baseURL: strings.TrimRight(parsed.String(), "/"), token: token, maxAttempts: maxAttempts,
		http: &http.Client{Timeout: 90 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}}, nil
}

type benchmarkIdentification struct {
	Ref struct {
		LibraryID string `json:"libraryId"`
		ItemID    string `json:"itemId"`
	} `json:"ref"`
	Title             string `json:"title"`
	StructureKind     string `json:"structureKind"`
	MatchState        string `json:"matchState"`
	ItemRevision      int64  `json:"itemRevision"`
	StructureRevision int64  `json:"structureRevision"`
	File              *struct {
		Filename  string `json:"filename"`
		Technical *struct {
			DurationMs int64 `json:"durationMs"`
		} `json:"technical"`
	} `json:"file"`
}

type benchmarkFillResponse struct {
	State    string          `json:"state"`
	Reason   string          `json:"reason,omitempty"`
	Match    json.RawMessage `json:"match"`
	Decision *struct {
		DryRun             bool                 `json:"dryRun"`
		Provider           string               `json:"provider"`
		Kind               string               `json:"kind"`
		ExternalID         string               `json:"externalId,omitempty"`
		Title              string               `json:"title,omitempty"`
		Year               string               `json:"year,omitempty"`
		Language           string               `json:"language"`
		Region             string               `json:"region"`
		Evidence           []string             `json:"evidence,omitempty"`
		Candidates         []benchmarkCandidate `json:"candidates,omitempty"`
		InputTitle         string               `json:"inputTitle,omitempty"`
		InputYear          int                  `json:"inputYear,omitempty"`
		Aliases            []string             `json:"aliases,omitempty"`
		DurationMs         int64                `json:"durationMs,omitempty"`
		Incomplete         bool                 `json:"incomplete"`
		Ambiguities        []string             `json:"ambiguities,omitempty"`
		ItemRevision       int64                `json:"itemRevision"`
		StructureRevision  int64                `json:"structureRevision"`
		PolicyRevision     int64                `json:"policyRevision"`
		LocalRulesRevision int64                `json:"localRulesRevision"`
		AutoTitleMatch     bool                 `json:"autoTitleMatch"`
		AdapterVersion     string               `json:"adapterVersion,omitempty"`
	} `json:"decision"`
}

func (client *benchmarkClient) runSample(ctx context.Context, sample benchmarkSample) benchmarkResult {
	started := time.Now()
	result := benchmarkResult{ID: sample.ID, LibraryID: sample.LibraryID, ItemID: sample.ItemID,
		SourceRoot: sample.SourceRoot, Corpus: sample.Corpus, Category: sample.Category, Rationale: sample.Rationale, Expected: sample.Expected}
	base := "/api/media-library/v1/libraries/" + url.PathEscape(sample.LibraryID) + "/items/" + url.PathEscape(sample.ItemID)
	var identification benchmarkIdentification
	if err := client.doJSON(ctx, http.MethodGet, base+"/identification", nil, &identification, &result.Requests); err != nil {
		result.Error = client.safeError(err)
		result.ElapsedMs = time.Since(started).Milliseconds()
		return result
	}
	if identification.Ref.LibraryID != sample.LibraryID || identification.Ref.ItemID != sample.ItemID ||
		identification.ItemRevision < 1 || identification.StructureRevision < 1 {
		result.Error = "identification response did not match the requested item and revisions"
		result.ElapsedMs = time.Since(started).Milliseconds()
		return result
	}
	if identification.File != nil {
		result.Filename = identification.File.Filename
		if identification.File.Technical != nil {
			result.Actual.DurationMs = identification.File.Technical.DurationMs
		}
	}
	body := map[string]any{"operationId": "benchmark-" + sample.ID, "itemRevision": identification.ItemRevision,
		"structureRevision": identification.StructureRevision, "dryRun": true}
	var response benchmarkFillResponse
	if err := client.doJSON(ctx, http.MethodPost, base+"/match-fill", body, &response, &result.Requests); err != nil {
		result.Error = client.safeError(err)
		result.ElapsedMs = time.Since(started).Milliseconds()
		return result
	}
	decision := response.Decision
	if decision == nil || !decision.DryRun || len(response.Match) != 0 && string(response.Match) != "null" {
		result.Error = "match-fill response was not a zero-write dry-run decision"
		result.ElapsedMs = time.Since(started).Milliseconds()
		return result
	}
	if decision.ItemRevision != identification.ItemRevision || decision.StructureRevision != identification.StructureRevision {
		result.Error = "dry-run decision revisions do not match identification"
		result.ElapsedMs = time.Since(started).Milliseconds()
		return result
	}
	if !validBenchmarkState(response.State) {
		result.Error = "dry-run returned an unsupported state"
		result.ElapsedMs = time.Since(started).Milliseconds()
		return result
	}
	result.Actual = benchmarkActual{State: response.State, Reason: response.Reason, Provider: decision.Provider,
		Kind: decision.Kind, ExternalID: decision.ExternalID, Title: decision.Title, Year: decision.Year,
		Language: decision.Language, Region: decision.Region, Evidence: decision.Evidence, Candidates: decision.Candidates,
		InputTitle: decision.InputTitle, InputYear: decision.InputYear, Aliases: decision.Aliases, DurationMs: decision.DurationMs,
		Incomplete: decision.Incomplete, Ambiguities: decision.Ambiguities, ItemRevision: decision.ItemRevision,
		StructureRevision: decision.StructureRevision, PolicyRevision: decision.PolicyRevision,
		LocalRulesRevision: decision.LocalRulesRevision, AutoTitleMatch: decision.AutoTitleMatch, AdapterVersion: decision.AdapterVersion}
	if result.Actual.DurationMs == 0 && identification.File != nil && identification.File.Technical != nil {
		result.Actual.DurationMs = identification.File.Technical.DurationMs
	}
	if response.State == "matched" && (decision.Provider == "" || decision.Kind == "" || decision.ExternalID == "") {
		result.Error = "matched dry-run decision lacks a complete identity"
		result.ElapsedMs = time.Since(started).Milliseconds()
		return result
	}
	requests := result.Requests
	elapsed := time.Since(started).Milliseconds()
	result = evaluateBenchmarkSample(sample, result.Actual)
	result.SourceRoot, result.Corpus, result.Category, result.Rationale = sample.SourceRoot, sample.Corpus, sample.Category, sample.Rationale
	result.Expected, result.Filename = sample.Expected, identification.FileName()
	result.Requests = requests
	result.ElapsedMs = elapsed
	return result
}

func (identification benchmarkIdentification) FileName() string {
	if identification.File == nil {
		return ""
	}
	return identification.File.Filename
}

func validBenchmarkState(state string) bool {
	return state == "matched" || state == "needs_review" || state == "no_match" || state == "skipped" || state == "local_only"
}

func (client *benchmarkClient) doJSON(ctx context.Context, method, path string, body any, target any, requests *int) error {
	var encodedBody []byte
	if body != nil {
		var err error
		encodedBody, err = json.Marshal(body)
		if err != nil {
			return err
		}
	}
	for attempt := 1; attempt <= client.maxAttempts; attempt++ {
		var reader io.Reader
		if body != nil {
			reader = bytes.NewReader(encodedBody)
		}
		request, err := http.NewRequestWithContext(ctx, method, client.baseURL+path, reader)
		if err != nil {
			return err
		}
		request.Header.Set("X-Auth", client.token)
		request.Header.Set("Accept", "application/json")
		if body != nil {
			request.Header.Set("Content-Type", "application/json")
		}
		*requests = *requests + 1
		response, err := client.http.Do(request)
		if err != nil {
			if attempt < client.maxAttempts && ctx.Err() == nil {
				time.Sleep(time.Duration(attempt) * 200 * time.Millisecond)
				continue
			}
			return errors.New("request failed")
		}
		data, readErr := io.ReadAll(io.LimitReader(response.Body, 1024*1024+1))
		_ = response.Body.Close()
		if readErr != nil {
			return errors.New("response read failed")
		}
		if len(data) > 1024*1024 {
			return errors.New("response too large")
		}
		if response.StatusCode < 200 || response.StatusCode >= 300 {
			if attempt < client.maxAttempts && retryableBenchmarkStatus(response.StatusCode) && ctx.Err() == nil {
				time.Sleep(time.Duration(attempt) * 500 * time.Millisecond)
				continue
			}
			return fmt.Errorf("HTTP %d", response.StatusCode)
		}
		decoder := json.NewDecoder(bytes.NewReader(data))
		if err := decoder.Decode(target); err != nil {
			return errors.New("invalid JSON response")
		}
		if err := decoder.Decode(new(any)); !errors.Is(err, io.EOF) {
			return errors.New("multiple JSON values in response")
		}
		return nil
	}
	return errors.New("request attempts exhausted")
}

func retryableBenchmarkStatus(status int) bool {
	return status == http.StatusTooManyRequests || status == http.StatusBadGateway || status == http.StatusServiceUnavailable || status == http.StatusGatewayTimeout
}

func (client *benchmarkClient) safeError(err error) string {
	message := err.Error()
	if client.token != "" {
		message = strings.ReplaceAll(message, client.token, "[redacted]")
	}
	return message
}

func evaluateBenchmarkSample(sample benchmarkSample, actual benchmarkActual) benchmarkResult {
	result := benchmarkResult{ID: sample.ID, LibraryID: sample.LibraryID, ItemID: sample.ItemID,
		SourceRoot: sample.SourceRoot, Corpus: sample.Corpus, Category: sample.Category, Rationale: sample.Rationale,
		Expected: sample.Expected, Actual: actual}
	result.StateMatch = actual.State == sample.Expected.State
	if sample.Expected.State == "matched" {
		result.IdentityMatch = actual.State == "matched" && actual.Provider == sample.Expected.Provider &&
			actual.Kind == sample.Expected.Kind && actual.ExternalID == sample.Expected.ExternalID
		result.AutomaticCorrect = result.IdentityMatch
		result.WrongAutomatic = actual.State == "matched" && !result.IdentityMatch
		result.Missed = actual.State != "matched"
		for index, candidate := range actual.Candidates {
			if candidate.Provider == sample.Expected.Provider && candidate.Kind == sample.Expected.Kind && candidate.ExternalID == sample.Expected.ExternalID {
				result.CandidateRank = index + 1
				break
			}
		}
	} else {
		result.IdentityMatch = actual.State != "matched"
		result.WrongAutomatic = actual.State == "matched"
	}
	result.RecallAt1 = result.CandidateRank > 0 && result.CandidateRank <= 1
	result.RecallAt3 = result.CandidateRank > 0 && result.CandidateRank <= 3
	result.RecallAt5 = result.CandidateRank > 0 && result.CandidateRank <= 5
	result.RecallAt10 = result.CandidateRank > 0 && result.CandidateRank <= 10
	if result.CandidateRank > 0 {
		result.ReciprocalRank = 1 / float64(result.CandidateRank)
	}
	return result
}

func summarizeBenchmark(results []benchmarkResult, gate benchmarkGate, sourceRevision string) benchmarkSummary {
	summary := benchmarkSummary{SourceRevision: sourceRevision, GeneratedAt: time.Now().UTC(), Gate: gate,
		ByCorpus: map[string]int{}, ByCategory: map[string]int{}, ByRoot: map[string]int{}, ByState: map[string]int{}}
	recall1, recall3, recall5, recall10 := 0, 0, 0, 0
	for _, result := range results {
		summary.TotalSamples++
		summary.ByCorpus[result.Corpus]++
		summary.ByCategory[result.Category]++
		if result.SourceRoot != "" {
			summary.ByRoot[result.SourceRoot]++
		}
		switch result.Corpus {
		case "real_nas":
			summary.RealNASSamples++
		case "authorized":
			summary.AuthorizedSamples++
		case "synthetic":
			summary.SyntheticSamples++
		}
		summary.Requests += result.Requests
		summary.ElapsedMs += result.ElapsedMs
		if result.Error != "" {
			summary.Errors++
			summary.ErrorIDs = append(summary.ErrorIDs, result.ID)
			continue
		}
		summary.ByState[result.Actual.State]++
		if result.StateMatch {
			summary.StateCorrect++
		}
		if result.Expected.State == "matched" {
			summary.ExpectedMatched++
			if result.AutomaticCorrect {
				summary.AutomaticCorrect++
			}
			if result.Missed {
				summary.Missed++
				summary.MissedIDs = append(summary.MissedIDs, result.ID)
			}
			if result.RecallAt1 {
				recall1++
			}
			if result.RecallAt3 {
				recall3++
			}
			if result.RecallAt5 {
				recall5++
			}
			if result.RecallAt10 {
				recall10++
			}
			summary.MRR += result.ReciprocalRank
		}
		if result.Actual.State == "matched" {
			summary.Automatic++
		}
		if result.WrongAutomatic {
			summary.WrongAutomatic++
			summary.WrongAutomaticIDs = append(summary.WrongAutomaticIDs, result.ID)
		}
		switch result.Actual.State {
		case "needs_review":
			summary.NeedsReview++
		case "no_match":
			summary.NoMatch++
		case "skipped":
			summary.Skipped++
		}
	}
	if summary.ExpectedMatched > 0 {
		summary.AutomaticCoverage = float64(summary.AutomaticCorrect) / float64(summary.ExpectedMatched)
		summary.RecallAt1 = float64(recall1) / float64(summary.ExpectedMatched)
		summary.RecallAt3 = float64(recall3) / float64(summary.ExpectedMatched)
		summary.RecallAt5 = float64(recall5) / float64(summary.ExpectedMatched)
		summary.RecallAt10 = float64(recall10) / float64(summary.ExpectedMatched)
		summary.MRR /= float64(summary.ExpectedMatched)
	}
	summary.StateAccuracy = ratio(summary.StateCorrect, summary.TotalSamples-summary.Errors)
	sort.Strings(summary.WrongAutomaticIDs)
	sort.Strings(summary.MissedIDs)
	sort.Strings(summary.ErrorIDs)
	summary.Failures = benchmarkGateFailures(summary, gate)
	summary.Passed = len(summary.Failures) == 0
	return summary
}

func ratio(value, total int) float64 {
	if total == 0 {
		return 0
	}
	return float64(value) / float64(total)
}

func benchmarkGateFailures(summary benchmarkSummary, gate benchmarkGate) []string {
	var failures []string
	if summary.TotalSamples < gate.MinSamples {
		failures = append(failures, fmt.Sprintf("samples %d < %d", summary.TotalSamples, gate.MinSamples))
	}
	if summary.RealNASSamples < gate.MinRealSamples {
		failures = append(failures, fmt.Sprintf("real NAS samples %d < %d", summary.RealNASSamples, gate.MinRealSamples))
	}
	for _, root := range gate.RequiredRoots {
		if summary.ByRoot[root] < gate.MinPerRoot {
			failures = append(failures, fmt.Sprintf("root %s samples %d < %d", root, summary.ByRoot[root], gate.MinPerRoot))
		}
	}
	if summary.WrongAutomatic != 0 {
		failures = append(failures, fmt.Sprintf("wrong automatic matches %d", summary.WrongAutomatic))
	}
	if summary.Errors != 0 {
		failures = append(failures, fmt.Sprintf("sample errors %d", summary.Errors))
	}
	if summary.ExpectedMatched == 0 || summary.AutomaticCoverage < gate.MinAutoCoverage {
		failures = append(failures, fmt.Sprintf("automatic coverage %.4f < %.4f", summary.AutomaticCoverage, gate.MinAutoCoverage))
	}
	if summary.StateAccuracy < gate.MinStateAccuracy {
		failures = append(failures, fmt.Sprintf("state accuracy %.4f < %.4f", summary.StateAccuracy, gate.MinStateAccuracy))
	}
	recall := summary.RecallAt5
	switch gate.RecallK {
	case 1:
		recall = summary.RecallAt1
	case 3:
		recall = summary.RecallAt3
	case 5:
		recall = summary.RecallAt5
	case 10:
		recall = summary.RecallAt10
	default:
		failures = append(failures, "recallK must be 1, 3, 5 or 10")
	}
	if recall < gate.MinRecall {
		failures = append(failures, fmt.Sprintf("Recall@%d %.4f < %.4f", gate.RecallK, recall, gate.MinRecall))
	}
	if summary.MRR < gate.MinMRR {
		failures = append(failures, fmt.Sprintf("MRR %.4f < %.4f", summary.MRR, gate.MinMRR))
	}
	return failures
}

func validBenchmarkSourceRevision(value string) bool {
	if len(value) != 40 {
		return false
	}
	for _, r := range value {
		if r >= '0' && r <= '9' || r >= 'a' && r <= 'f' || r >= 'A' && r <= 'F' {
			continue
		}
		return false
	}
	return true
}

func validateBenchmarkGate(gate benchmarkGate) error {
	if gate.MinSamples < 1 || gate.MinRealSamples < 0 || gate.MinRealSamples > gate.MinSamples {
		return errors.New("invalid sample thresholds")
	}
	if gate.MinPerRoot < 0 || len(gate.RequiredRoots) > 0 && gate.MinPerRoot < 1 {
		return errors.New("invalid per-root threshold")
	}
	seen := make(map[string]struct{}, len(gate.RequiredRoots))
	for _, root := range gate.RequiredRoots {
		if strings.TrimSpace(root) == "" || root != strings.TrimSpace(root) || len(root) > 1024 || strings.ContainsFunc(root, unicode.IsControl) {
			return errors.New("invalid required root")
		}
		if _, exists := seen[root]; exists {
			return errors.New("duplicate required root")
		}
		seen[root] = struct{}{}
	}
	for _, threshold := range []float64{gate.MinAutoCoverage, gate.MinStateAccuracy, gate.MinRecall, gate.MinMRR} {
		if threshold < 0 || threshold > 1 {
			return errors.New("quality thresholds must be between 0 and 1")
		}
	}
	if gate.RecallK != 1 && gate.RecallK != 3 && gate.RecallK != 5 && gate.RecallK != 10 {
		return errors.New("recallK must be 1, 3, 5 or 10")
	}
	return nil
}

func readBenchmarkToken(path string) (string, error) {
	file, err := os.Open(path)
	if err != nil {
		return "", errors.New("token file cannot be read")
	}
	defer file.Close()
	data, err := io.ReadAll(io.LimitReader(file, 16*1024+1))
	if err != nil || len(data) > 16*1024 {
		return "", errors.New("token file is unreadable or too large")
	}
	token := strings.TrimSpace(string(data))
	if token == "" || strings.ContainsAny(token, "\r\n") {
		return "", errors.New("token file is empty or invalid")
	}
	return token, nil
}

func runBenchmarkSamples(ctx context.Context, client *benchmarkClient, samples []benchmarkSample, progress func(int, benchmarkResult)) []benchmarkResult {
	results := make([]benchmarkResult, 0, len(samples))
	for index, sample := range samples {
		result := client.runSample(ctx, sample)
		results = append(results, result)
		if progress != nil {
			progress(index+1, result)
		}
	}
	return results
}

func writeBenchmarkOutputs(resultPath, summaryPath string, results []benchmarkResult, summary benchmarkSummary) error {
	if err := writeJSONFile(resultPath, func(writer io.Writer) error {
		encoder := json.NewEncoder(writer)
		for _, result := range results {
			if err := encoder.Encode(result); err != nil {
				return err
			}
		}
		return nil
	}); err != nil {
		return fmt.Errorf("write results: %w", err)
	}
	if err := writeJSONFile(summaryPath, func(writer io.Writer) error {
		encoder := json.NewEncoder(writer)
		encoder.SetIndent("", "  ")
		return encoder.Encode(summary)
	}); err != nil {
		return fmt.Errorf("write summary: %w", err)
	}
	return nil
}

func writeJSONFile(path string, write func(io.Writer) error) error {
	if strings.TrimSpace(path) == "" {
		return errors.New("output path is required")
	}
	file, err := os.OpenFile(path, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o600)
	if err != nil {
		return err
	}
	if err := write(file); err != nil {
		_ = file.Close()
		return err
	}
	if err := file.Sync(); err != nil {
		_ = file.Close()
		return err
	}
	return file.Close()
}
