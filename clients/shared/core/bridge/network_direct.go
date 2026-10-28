//go:build fileway_direct

package bridge

import (
	"context"
	"errors"
	"net/http"
)

// This build deliberately has no dependency on tailnet or tailscale.com.
// Reject embedded requests explicitly; never silently redirect them to direct.
var errEmbeddedUnavailable = errors.New("embedded networking is unavailable in this build")

type platformNetwork struct{}
type networkStatus struct {
	State string `json:"state"`
}
type networkNode struct{}

func newNetworkNode(string, string, []byte) (*networkNode, error) { return nil, errEmbeddedUnavailable }
func updatePlatformNetwork(platformNetwork) error                 { return errEmbeddedUnavailable }
func (*networkNode) Client(context.Context) (*http.Client, error) { return nil, errEmbeddedUnavailable }
func (*networkNode) Start(context.Context) (networkStatus, error) {
	return networkStatus{}, errEmbeddedUnavailable
}
func (*networkNode) Status(context.Context) (networkStatus, error) {
	return networkStatus{}, errEmbeddedUnavailable
}
func (*networkNode) Stop(context.Context) error   { return errEmbeddedUnavailable }
func (*networkNode) Logout(context.Context) error { return errEmbeddedUnavailable }
func (*networkNode) Close() error                 { return nil }
func (*networkNode) PlatformNetworkChanged()      {}
