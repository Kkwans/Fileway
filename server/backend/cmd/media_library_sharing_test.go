package cmd

import (
	"strings"
	"testing"
)

func TestMediaLibrarySharingPolicyFlagDefaultsAndEnvironment(t *testing.T) {
	flag := rootCmd.Flags().Lookup("disableMediaLibrarySharing")
	if flag == nil || flag.DefValue != "false" {
		t.Fatalf("sharing policy flag default: %+v", flag)
	}
	replacer := strings.NewReplacer(generateEnvKeyReplacements(rootCmd)...)
	if got := replacer.Replace("DISABLEMEDIALIBRARYSHARING"); got != "DISABLE_MEDIA_LIBRARY_SHARING" {
		t.Fatalf("sharing policy environment key: %q", got)
	}
}
