//go:build !fileway_direct

package bridge

import "github.com/Kkwans/nas-file-browser-client/core/tailnet"

// The default mobile build retains the embedded networking implementation.
type networkNode = tailnet.Node
type platformNetwork = tailnet.PlatformNetwork
type networkStatus = tailnet.Status

var newNetworkNode = tailnet.NewNode
var updatePlatformNetwork = tailnet.UpdatePlatformNetwork
