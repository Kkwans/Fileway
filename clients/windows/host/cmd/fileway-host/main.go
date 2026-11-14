// fileway-host is a supervised private-pipe adapter for the shared Go Engine.
package main

import (
	"io"
	"log"
	"os"

	"github.com/Kkwans/Fileway/clients/windows/host/internal/ipc"
	"github.com/Kkwans/nas-file-browser-client/core/bridge"
)

// Set with -ldflags "-X main.hostVersion=... -X main.buildCommit=...".
var (
	hostVersion = "0.1.0"
	buildCommit = ""
)

func main() {
	// Dependencies may use the default logger; private data must never become
	// inherited-pipe diagnostics. The Host emits only its fixed safe vocabulary.
	log.SetOutput(io.Discard)
	manifest := ipc.BuildManifest(hostVersion, buildCommit, ipc.ShutdownTimeout)
	server := ipc.NewServer(&bridge.Engine{}, manifest)
	if err := server.Run(os.Stdin, os.Stdout, os.Stderr); err != nil {
		os.Exit(1)
	}
}
