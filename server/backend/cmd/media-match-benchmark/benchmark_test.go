package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestLoadBenchmarkManifestValidatesGroundTruth(t *testing.T) {
	dir := t.TempDir()
	valid := benchmarkSample{
		ID: "real-001", LibraryID: "lib_1", ItemID: "item_1", SourceRoot: "/home/Kkwans/Movie/115",
		Corpus: "real_nas", Category: "clean_movie", Rationale: "filename and year identify one film",
		Expected: benchmarkExpected{State: "matched", Provider: "tmdb", Kind: "movie", ExternalID: "42"},
	}
	writeManifest(t, filepath.Join(dir, "valid.jsonl"), valid)
	samples, err := loadBenchmarkManifest(filepath.Join(dir, "valid.jsonl"))
	if err != nil || len(samples) != 1 {
		t.Fatalf("valid manifest: samples=%+v err=%v", samples, err)
	}

	invalid := []benchmarkSample{
		{ID: "missing-root", LibraryID: "lib_1", ItemID: "item_1", Corpus: "real_nas", Category: "clean_movie", Rationale: "missing root", Expected: benchmarkExpected{State: "no_match"}},
		{ID: "missing-identity", LibraryID: "lib_1", ItemID: "item_1", Corpus: "synthetic", Category: "clean_movie", Rationale: "missing identity", Expected: benchmarkExpected{State: "matched"}},
		{ID: "bad-state", LibraryID: "lib_1", ItemID: "item_1", Corpus: "synthetic", Category: "conflict", Rationale: "bad state", Expected: benchmarkExpected{State: "maybe"}},
		{ID: "missing-rationale", LibraryID: "lib_1", ItemID: "item_1", Corpus: "synthetic", Category: "conflict", Expected: benchmarkExpected{State: "needs_review"}},
	}
	for _, sample := range invalid {
		writeManifest(t, filepath.Join(dir, sample.ID+".jsonl"), sample)
		if _, err := loadBenchmarkManifest(filepath.Join(dir, sample.ID+".jsonl")); err == nil {
			t.Fatalf("invalid sample accepted: %+v", sample)
		}
	}

	writeManifest(t, filepath.Join(dir, "duplicate.jsonl"), valid, valid)
	if _, err := loadBenchmarkManifest(filepath.Join(dir, "duplicate.jsonl")); err == nil {
		t.Fatal("duplicate sample ID accepted")
	}
}

func TestEvaluateBenchmarkSampleClassifiesAutomaticErrorsAndMisses(t *testing.T) {
	sample := benchmarkSample{ID: "case-1", Expected: benchmarkExpected{State: "matched", Provider: "tmdb", Kind: "movie", ExternalID: "42"}}
	correct := evaluateBenchmarkSample(sample, benchmarkActual{
		State: "matched", Provider: "tmdb", Kind: "movie", ExternalID: "42",
		Candidates: []benchmarkCandidate{{Provider: "tmdb", Kind: "movie", ExternalID: "43"}, {Provider: "tmdb", Kind: "movie", ExternalID: "42"}},
	})
	if !correct.StateMatch || !correct.IdentityMatch || !correct.AutomaticCorrect || correct.WrongAutomatic || correct.Missed ||
		correct.CandidateRank != 2 || !correct.RecallAt3 || correct.RecallAt1 || correct.ReciprocalRank != 0.5 {
		t.Fatalf("correct match metrics: %+v", correct)
	}

	wrong := evaluateBenchmarkSample(sample, benchmarkActual{State: "matched", Provider: "tmdb", Kind: "movie", ExternalID: "43"})
	if wrong.WrongAutomatic != true || wrong.AutomaticCorrect || wrong.Missed {
		t.Fatalf("wrong automatic match not classified: %+v", wrong)
	}

	missed := evaluateBenchmarkSample(sample, benchmarkActual{
		State: "needs_review", Reason: "ambiguous_candidates",
		Candidates: []benchmarkCandidate{{Provider: "tmdb", Kind: "movie", ExternalID: "42"}},
	})
	if !missed.Missed || missed.StateMatch || missed.CandidateRank != 1 || !missed.RecallAt1 || missed.ReciprocalRank != 1 {
		t.Fatalf("missed match metrics: %+v", missed)
	}

	conflict := benchmarkSample{ID: "conflict", Expected: benchmarkExpected{State: "needs_review"}}
	abstained := evaluateBenchmarkSample(conflict, benchmarkActual{State: "needs_review", Reason: "ambiguous_candidates"})
	if !abstained.StateMatch || !abstained.IdentityMatch || abstained.WrongAutomatic {
		t.Fatalf("correct abstention: %+v", abstained)
	}
	unsafe := evaluateBenchmarkSample(conflict, benchmarkActual{State: "matched", Provider: "tmdb", Kind: "movie", ExternalID: "42"})
	if !unsafe.WrongAutomatic || unsafe.StateMatch {
		t.Fatalf("automatic match on conflict not rejected: %+v", unsafe)
	}
}

func TestBenchmarkSummaryGateRequiresCoverageAndZeroAutomaticErrors(t *testing.T) {
	sample := benchmarkSample{ID: "real-1", SourceRoot: "/home/Kkwans/Movie/115", Corpus: "real_nas", Category: "clean_movie",
		Expected: benchmarkExpected{State: "matched", Provider: "tmdb", Kind: "movie", ExternalID: "42"}}
	result := evaluateBenchmarkSample(sample, benchmarkActual{State: "matched", Provider: "tmdb", Kind: "movie", ExternalID: "42",
		Candidates: []benchmarkCandidate{{Provider: "tmdb", Kind: "movie", ExternalID: "42"}}})
	gate := benchmarkGate{MinSamples: 1, MinRealSamples: 1, RequiredRoots: []string{"/home/Kkwans/Movie/115", "/home/Kkwans/TV"},
		MinPerRoot: 1, MinAutoCoverage: .85, RecallK: 5, MinRecall: .95, MinMRR: .9}
	summary := summarizeBenchmark([]benchmarkResult{result}, gate, "0123456789012345678901234567890123456789")
	if summary.Passed || len(summary.Failures) == 0 {
		t.Fatalf("missing TV root passed gate: %+v", summary)
	}

	gate.RequiredRoots = []string{"/home/Kkwans/Movie/115"}
	summary = summarizeBenchmark([]benchmarkResult{result}, gate, "0123456789012345678901234567890123456789")
	if !summary.Passed || summary.RecallAt5 != 1 || summary.MRR != 1 || summary.AutomaticCoverage != 1 {
		t.Fatalf("valid summary failed: %+v", summary)
	}

	wrong := evaluateBenchmarkSample(sample, benchmarkActual{State: "matched", Provider: "tmdb", Kind: "movie", ExternalID: "43"})
	summary = summarizeBenchmark([]benchmarkResult{wrong}, gate, "0123456789012345678901234567890123456789")
	if summary.Passed || summary.WrongAutomatic != 1 {
		t.Fatalf("wrong automatic match passed gate: %+v", summary)
	}

	failed := result
	failed.Error = "provider unavailable"
	summary = summarizeBenchmark([]benchmarkResult{failed}, gate, "0123456789012345678901234567890123456789")
	if summary.Passed || summary.Errors != 1 {
		t.Fatalf("provider error passed gate: %+v", summary)
	}
}

func TestBenchmarkClientUsesIdentificationAndZeroWriteDryRun(t *testing.T) {
	var requests []string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests = append(requests, r.Method+" "+r.URL.Path)
		if r.Header.Get("X-Auth") != "fixture-token" {
			t.Errorf("missing auth header")
		}
		w.Header().Set("Content-Type", "application/json")
		switch {
		case r.Method == http.MethodGet && strings.HasSuffix(r.URL.Path, "/identification"):
			_, _ = w.Write([]byte(`{"ref":{"libraryId":"lib_1","itemId":"item_1"},"title":"Echo","structureKind":"video","matchState":"unmatched","itemRevision":7,"structureRevision":3,"aliases":["Echo"],"externalIds":[],"file":{"filename":"Echo.2020.mkv","technical":{"durationMs":7200000}}}`))
		case r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/match-fill"):
			var body map[string]any
			if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
				t.Errorf("decode body: %v", err)
			}
			if body["dryRun"] != true || body["itemRevision"] != float64(7) || body["structureRevision"] != float64(3) {
				t.Errorf("unsafe benchmark body: %+v", body)
			}
			_, _ = w.Write([]byte(`{"state":"matched","decision":{"dryRun":true,"provider":"tmdb","kind":"movie","externalId":"42","title":"Echo","year":"2020","language":"zh-CN","region":"CN","evidence":["title_match"],"inputTitle":"Echo","inputYear":2020,"durationMs":7200000,"itemRevision":7,"structureRevision":3,"policyRevision":2,"localRulesRevision":18,"autoTitleMatch":true,"adapterVersion":"tmdb-v1","candidates":[{"provider":"tmdb","kind":"movie","externalId":"42","title":"Echo","year":2020,"evidence":["title_match"]}]}}`))
		default:
			http.Error(w, "unexpected", http.StatusNotFound)
		}
	}))
	defer server.Close()

	client, err := newBenchmarkClient(server.URL, "fixture-token", 1)
	if err != nil {
		t.Fatal(err)
	}
	sample := benchmarkSample{ID: "case-1", LibraryID: "lib_1", ItemID: "item_1", Corpus: "real_nas", SourceRoot: "/home/Kkwans/Movie/115",
		Category: "clean_movie", Rationale: "verified fixture", Expected: benchmarkExpected{State: "matched", Provider: "tmdb", Kind: "movie", ExternalID: "42"}}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	result := client.runSample(ctx, sample)
	if result.Error != "" || result.Actual.State != "matched" || result.Actual.ExternalID != "42" ||
		result.Actual.DurationMs != 7200000 || result.Filename != "Echo.2020.mkv" || result.Requests != 2 || len(result.Actual.Candidates) != 1 {
		t.Fatalf("benchmark client result: %+v", result)
	}
	if len(requests) != 2 || !strings.HasPrefix(requests[0], "GET ") || !strings.HasPrefix(requests[1], "POST ") {
		t.Fatalf("benchmark request order: %v", requests)
	}
}

func TestWriteBenchmarkOutputsDoesNotPersistToken(t *testing.T) {
	dir := t.TempDir()
	results := []benchmarkResult{{ID: "case-1", Error: "fixture failure"}}
	summary := benchmarkSummary{Passed: false, Failures: []string{"fixture failure"}}
	resultPath := filepath.Join(dir, "results.jsonl")
	summaryPath := filepath.Join(dir, "summary.json")
	if err := writeBenchmarkOutputs(resultPath, summaryPath, results, summary); err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{resultPath, summaryPath} {
		data, err := os.ReadFile(path)
		if err != nil {
			t.Fatal(err)
		}
		if strings.Contains(string(data), "fixture-token") {
			t.Fatalf("token persisted in %s", path)
		}
	}
}

func TestBenchmarkClientRedactsTokenFromErrors(t *testing.T) {
	client, err := newBenchmarkClient("http://127.0.0.1:8888", "fixture-token", 1)
	if err != nil {
		t.Fatal(err)
	}
	message := client.safeError(fmt.Errorf("request failed with %s", "fixture-token"))
	if strings.Contains(message, "fixture-token") || !strings.Contains(message, "[redacted]") {
		t.Fatalf("token redaction failed: %s", message)
	}
}

func TestBenchmarkSafetyValidation(t *testing.T) {
	validGate := benchmarkGate{MinSamples: 300, MinRealSamples: 300, MinPerRoot: 1,
		RequiredRoots:   []string{"/home/Kkwans/Movie/115", "/home/Kkwans/TV"},
		MinAutoCoverage: .85, MinStateAccuracy: .95, RecallK: 5, MinRecall: .95, MinMRR: .9}
	if err := validateBenchmarkGate(validGate); err != nil {
		t.Fatalf("valid gate rejected: %v", err)
	}
	invalid := []benchmarkGate{
		{MinSamples: 0},
		{MinSamples: 1, MinRealSamples: 2},
		{MinSamples: 1, MinAutoCoverage: 1.1},
		{MinSamples: 1, MinStateAccuracy: -.1},
		{MinSamples: 1, RecallK: 4},
		{MinSamples: 1, MinRecall: 1.1},
		{MinSamples: 1, MinMRR: -.1},
		{MinSamples: 1, RequiredRoots: []string{" "}, MinPerRoot: 1},
		{MinSamples: 1, RequiredRoots: []string{"/home/Kkwans/TV"}, MinPerRoot: 0},
	}
	for _, gate := range invalid {
		if err := validateBenchmarkGate(gate); err == nil {
			t.Fatalf("invalid gate accepted: %+v", gate)
		}
	}
	if !validBenchmarkSourceRevision("0123456789abcdef0123456789abcdef01234567") {
		t.Fatal("valid source revision rejected")
	}
	for _, revision := range []string{"", "main", "012345", "0123456789abcdef0123456789abcdef0123456g"} {
		if validBenchmarkSourceRevision(revision) {
			t.Fatalf("invalid source revision accepted: %q", revision)
		}
	}
}

func TestReadBenchmarkTokenIsBoundedAndTrimmed(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "token")
	if err := os.WriteFile(path, []byte("  fixture-token\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	token, err := readBenchmarkToken(path)
	if err != nil || token != "fixture-token" {
		t.Fatalf("token read: %q %v", token, err)
	}
	if err := os.WriteFile(path, []byte(strings.Repeat("x", 16*1024+1)), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := readBenchmarkToken(path); err == nil {
		t.Fatal("oversized token accepted")
	}
}

func TestRunBenchmarkSamplesContinuesAfterErrors(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if strings.Contains(r.URL.Path, "item_1/") {
			http.Error(w, "fixture failure", http.StatusBadGateway)
			return
		}
		switch {
		case strings.HasSuffix(r.URL.Path, "/identification"):
			_, _ = w.Write([]byte(`{"ref":{"libraryId":"lib_1","itemId":"item_2"},"title":"Echo","structureKind":"video","matchState":"unmatched","itemRevision":7,"structureRevision":3,"file":{"filename":"Echo.2020.mkv"}}`))
		case strings.HasSuffix(r.URL.Path, "/match-fill"):
			_, _ = w.Write([]byte(`{"state":"matched","decision":{"dryRun":true,"provider":"tmdb","kind":"movie","externalId":"42","title":"Echo","year":"2020","language":"zh-CN","region":"CN","itemRevision":7,"structureRevision":3,"policyRevision":2,"localRulesRevision":18,"candidates":[{"provider":"tmdb","kind":"movie","externalId":"42"}]}}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()
	client, err := newBenchmarkClient(server.URL, "fixture-token", 1)
	if err != nil {
		t.Fatal(err)
	}
	samples := []benchmarkSample{
		{ID: "case-1", LibraryID: "lib_1", ItemID: "item_1", Corpus: "synthetic", Category: "clean_movie", Rationale: "fixture", Expected: benchmarkExpected{State: "matched", Provider: "tmdb", Kind: "movie", ExternalID: "42"}},
		{ID: "case-2", LibraryID: "lib_1", ItemID: "item_2", Corpus: "synthetic", Category: "clean_movie", Rationale: "fixture", Expected: benchmarkExpected{State: "matched", Provider: "tmdb", Kind: "movie", ExternalID: "42"}},
	}
	results := runBenchmarkSamples(context.Background(), client, samples, nil)
	if len(results) != 2 || results[0].Error == "" || results[1].Error != "" || !results[1].AutomaticCorrect {
		t.Fatalf("runner did not continue in order: %+v", results)
	}
}

func writeManifest(t *testing.T, path string, samples ...benchmarkSample) {
	t.Helper()
	file, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	encoder := json.NewEncoder(file)
	for _, sample := range samples {
		if err := encoder.Encode(sample); err != nil {
			t.Fatal(err)
		}
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}
}
