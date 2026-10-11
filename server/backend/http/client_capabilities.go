package fbhttp

import "net/http"

// Expose only the non-secret policy needed by authenticated native clients.
// Administrative settings, keys, paths and command definitions stay private.
var clientCapabilitiesHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	return renderJSON(w, r, struct {
		AuthMethod             string `json:"authMethod"`
		EnableExec             bool   `json:"enableExec"`
		MinimumPasswordLength  uint   `json:"minimumPasswordLength"`
		ConditionalTextSave    bool   `json:"conditionalTextSave"`
		ResourceWireOperations bool   `json:"resourceWireOperations"`
		SearchWirePathV1       bool   `json:"searchWirePathV1"`
	}{string(d.settings.AuthMethod), d.server.EnableExec, d.settings.MinimumPasswordLength, true, true, true})
})
