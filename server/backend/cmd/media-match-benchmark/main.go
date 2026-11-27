package main

import (
	"context"
	"flag"
	"fmt"
	"os"
	"strings"
	"time"
)

type benchmarkRoots []string

func (roots *benchmarkRoots) String() string { return strings.Join(*roots, ",") }

func (roots *benchmarkRoots) Set(value string) error {
	*roots = append(*roots, value)
	return nil
}

func main() {
	baseURL := flag.String("base-url", "", "Fileway server origin")
	tokenFile := flag.String("token-file", "", "file containing the Fileway JWT")
	manifest := flag.String("manifest", "", "JSONL ground-truth manifest")
	resultsPath := flag.String("results", "", "output JSONL report")
	summaryPath := flag.String("summary", "", "output JSON summary")
	sourceRevision := flag.String("source-revision", "", "exact 40-character source revision")
	minSamples := flag.Int("min-samples", 300, "minimum total samples")
	minRealSamples := flag.Int("min-real-samples", 0, "minimum real NAS samples")
	minPerRoot := flag.Int("min-per-root", 1, "minimum samples for each required root")
	minAutoCoverage := flag.Float64("min-auto-coverage", .85, "minimum automatic confirmation coverage")
	minStateAccuracy := flag.Float64("min-state-accuracy", .95, "minimum expected-state accuracy")
	recallK := flag.Int("recall-k", 5, "candidate recall cutoff: 1, 3, 5 or 10")
	minRecall := flag.Float64("min-recall", .95, "minimum Recall@K")
	minMRR := flag.Float64("min-mrr", .9, "minimum MRR")
	maxAttempts := flag.Int("max-attempts", 2, "maximum attempts per safe request")
	timeout := flag.Duration("timeout", 30*time.Minute, "overall benchmark timeout")
	var roots benchmarkRoots
	flag.Var(&roots, "required-root", "required real NAS root; repeat for multiple roots")
	flag.Parse()

	if err := runBenchmarkCommand(*baseURL, *tokenFile, *manifest, *resultsPath, *summaryPath, *sourceRevision,
		*minSamples, *minRealSamples, roots, *minPerRoot, *minAutoCoverage, *minStateAccuracy, *recallK, *minRecall, *minMRR,
		*maxAttempts, *timeout); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func runBenchmarkCommand(baseURL, tokenFile, manifest, resultsPath, summaryPath, sourceRevision string,
	minSamples, minRealSamples int, roots []string, minPerRoot int, minAutoCoverage, minStateAccuracy float64,
	recallK int, minRecall, minMRR float64, maxAttempts int, timeout time.Duration) error {
	if !validBenchmarkSourceRevision(sourceRevision) {
		return fmt.Errorf("source-revision must be an exact 40-character commit")
	}
	gate := benchmarkGate{MinSamples: minSamples, MinRealSamples: minRealSamples, RequiredRoots: roots,
		MinPerRoot: minPerRoot, MinAutoCoverage: minAutoCoverage, MinStateAccuracy: minStateAccuracy,
		RecallK: recallK, MinRecall: minRecall, MinMRR: minMRR}
	if err := validateBenchmarkGate(gate); err != nil {
		return err
	}
	if timeout <= 0 || timeout > 2*time.Hour {
		return fmt.Errorf("timeout must be between 1s and 2h")
	}
	samples, err := loadBenchmarkManifest(manifest)
	if err != nil {
		return err
	}
	if len(samples) < gate.MinSamples {
		return fmt.Errorf("manifest has %d samples, below minimum %d", len(samples), gate.MinSamples)
	}
	token := ""
	if tokenFile != "" {
		token, err = readBenchmarkToken(tokenFile)
		if err != nil {
			return err
		}
	} else {
		token = strings.TrimSpace(os.Getenv("FILEWAY_BENCHMARK_TOKEN"))
	}
	client, err := newBenchmarkClient(baseURL, token, maxAttempts)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	results := runBenchmarkSamples(ctx, client, samples, func(index int, result benchmarkResult) {
		status := result.Actual.State
		if result.Error != "" {
			status = "error"
		}
		fmt.Fprintf(os.Stderr, "%d/%d %s %s\n", index, len(samples), result.ID, status)
	})
	summary := summarizeBenchmark(results, gate, sourceRevision)
	if err := writeBenchmarkOutputs(resultsPath, summaryPath, results, summary); err != nil {
		return err
	}
	fmt.Printf("samples=%d real_nas=%d wrong_automatic=%d errors=%d auto_coverage=%.4f recall@%d=%.4f mrr=%.4f passed=%t\n",
		summary.TotalSamples, summary.RealNASSamples, summary.WrongAutomatic, summary.Errors,
		summary.AutomaticCoverage, gate.RecallK, benchmarkRecall(summary, gate.RecallK), summary.MRR, summary.Passed)
	if !summary.Passed {
		return fmt.Errorf("benchmark gate failed: %s", strings.Join(summary.Failures, "; "))
	}
	return nil
}

func benchmarkRecall(summary benchmarkSummary, k int) float64 {
	switch k {
	case 1:
		return summary.RecallAt1
	case 3:
		return summary.RecallAt3
	case 5:
		return summary.RecallAt5
	default:
		return summary.RecallAt10
	}
}
