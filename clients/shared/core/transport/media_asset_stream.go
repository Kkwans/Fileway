package transport

import (
	"io"
	"net/http"
	"strings"
)

func isSourceAssetStream(contentType string) bool {
	mediaType, _, _ := strings.Cut(contentType, ";")
	return strings.EqualFold(strings.TrimSpace(mediaType), "application/x-fileway-media-asset+ndjson")
}

// Forward small source-subtitle heartbeats immediately. Ordinary media retains
// its existing buffered path, and telemetry continues to count actual bytes.
type sourceAssetFlushWriter struct {
	writer     io.Writer
	controller *http.ResponseController
}

func (writer sourceAssetFlushWriter) Write(value []byte) (int, error) {
	n, err := writer.writer.Write(value)
	if err == nil {
		err = writer.controller.Flush()
	}
	return n, err
}
