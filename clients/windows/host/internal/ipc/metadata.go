package ipc

import (
	"runtime"
	"runtime/debug"
	"sort"
	"time"
)

type ModuleInfo struct {
	Path    string      `json:"path"`
	Version string      `json:"version"`
	Sum     string      `json:"sum,omitempty"`
	Replace *ModuleInfo `json:"replace,omitempty"`
}

type Capabilities struct {
	Framing                     string   `json:"framing"`
	MaxFrameBytes               int      `json:"maxFrameBytes"`
	MaxCoreCommandBytes         int      `json:"maxCoreCommandBytes"`
	BusinessWorkers             int      `json:"businessWorkers"`
	BusinessQueueCapacity       int      `json:"businessQueueCapacity"`
	ControlWorkers              int      `json:"controlWorkers"`
	ControlQueueCapacity        int      `json:"controlQueueCapacity"`
	MaxQueuedResponseBytes      int      `json:"maxQueuedResponseBytes"`
	ShutdownTimeoutMilliseconds int64    `json:"shutdownTimeoutMilliseconds"`
	RawBody                     bool     `json:"rawBody"`
	Operations                  []string `json:"operations"`
}

type Manifest struct {
	ProtocolMajor int               `json:"protocolMajor"`
	ProtocolMinor int               `json:"protocolMinor"`
	CoreProtocol  int               `json:"coreProtocol"`
	HostVersion   string            `json:"hostVersion"`
	BuildCommit   string            `json:"buildCommit"`
	GoVersion     string            `json:"goVersion"`
	Module        ModuleInfo        `json:"module"`
	Dependencies  []ModuleInfo      `json:"dependencies"`
	BuildSettings map[string]string `json:"buildSettings"`
	Capabilities  Capabilities      `json:"capabilities"`
}

func BuildManifest(version, commit string, timeout time.Duration) Manifest {
	if version == "" {
		version = "0.1.0"
	}
	manifest := Manifest{ProtocolMajor: ProtocolMajor, ProtocolMinor: ProtocolMinor, CoreProtocol: 1,
		HostVersion: version, BuildCommit: commit, GoVersion: runtime.Version(), Dependencies: []ModuleInfo{}, BuildSettings: map[string]string{},
		Capabilities: Capabilities{Framing: "uint32-le-utf8-json", MaxFrameBytes: MaxFrameBytes, MaxCoreCommandBytes: MaxCoreCommandBytes,
			BusinessWorkers: BusinessWorkers, BusinessQueueCapacity: BusinessQueueCapacity, ControlWorkers: ControlWorkers,
			ControlQueueCapacity: ControlQueueCapacity, MaxQueuedResponseBytes: MaxQueuedResponseBytes,
			ShutdownTimeoutMilliseconds: timeout.Milliseconds(), Operations: []string{}}}
	for name := range operations {
		manifest.Capabilities.Operations = append(manifest.Capabilities.Operations, name)
	}
	sort.Strings(manifest.Capabilities.Operations)
	if info, ok := debug.ReadBuildInfo(); ok {
		manifest.GoVersion = info.GoVersion
		manifest.Module = moduleInfo(info.Main)
		for _, dependency := range info.Deps {
			manifest.Dependencies = append(manifest.Dependencies, moduleInfo(*dependency))
		}
		for _, setting := range info.Settings {
			switch setting.Key {
			case "-buildmode", "-compiler", "CGO_ENABLED", "GOARCH", "GOOS", "GOAMD64", "vcs", "vcs.revision", "vcs.time", "vcs.modified":
				manifest.BuildSettings[setting.Key] = setting.Value
			}
		}
		if manifest.BuildCommit == "" {
			manifest.BuildCommit = manifest.BuildSettings["vcs.revision"]
		}
	}
	if manifest.BuildCommit == "" {
		manifest.BuildCommit = "unknown"
	}
	return manifest
}

func moduleInfo(module debug.Module) ModuleInfo {
	info := ModuleInfo{Path: module.Path, Version: module.Version, Sum: module.Sum}
	if module.Replace != nil {
		replacement := moduleInfo(*module.Replace)
		info.Replace = &replacement
	}
	return info
}
