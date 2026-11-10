package fbhttp

import "net/http"

// Expose only the non-secret policy needed by authenticated native clients.
// Administrative settings, keys, paths and command definitions stay private.
var clientCapabilitiesHandler = clientCapabilities(0)

func clientCapabilities(tvUpdateVersion int) handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		return renderJSON(w, r, struct {
			AuthMethod             string `json:"authMethod"`
			EnableExec             bool   `json:"enableExec"`
			MinimumPasswordLength  uint   `json:"minimumPasswordLength"`
			ConditionalTextSave    bool   `json:"conditionalTextSave"`
			ResourceWireOperations bool   `json:"resourceWireOperations"`
			MediaPlaybackVersion   int    `json:"mediaPlaybackVersion"`
			SourceSubtitles        bool   `json:"sourceSubtitles"`
			SubtitleAttachments    bool   `json:"subtitleAttachments"`
			SDRCompatibility       bool   `json:"sdrCompatibility"`
			SourcePGSStreaming     int    `json:"sourcePGSStreaming"`
			SourceASSStreaming     int    `json:"sourceASSStreaming"`
			TVUpdateProxyVersion   int    `json:"tvUpdateProxyVersion,omitempty"`
		}{string(d.settings.AuthMethod), d.server.EnableExec, d.settings.MinimumPasswordLength, true, true, 1, true, true, true, 1, 1, tvUpdateVersion})
	})
}
