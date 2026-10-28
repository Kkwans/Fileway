package discovery

import (
	"github.com/hashicorp/mdns"
	"net"
	"os"
	"reflect"
	"testing"
	"time"
)

func TestDiscoveryEndpointUsesActualPortTLSAndBasePath(t *testing.T) {
	port, txt, err := endpoint(&net.TCPAddr{Port: 41235}, Options{BasePath: "/fileway", TLS: true})
	if err != nil || port != 41235 || !reflect.DeepEqual(txt, []string{"version=1", "scheme=https", "path=/fileway"}) {
		t.Fatalf("port=%d txt=%v err=%v", port, txt, err)
	}
}
func TestDiscoveryPublicEndpointSupportsProxyPorts(t *testing.T) {
	port, txt, err := endpoint(&net.TCPAddr{Port: 8080}, Options{PublicURL: "https://media.example:9443/files"})
	if err != nil || port != 9443 || !reflect.DeepEqual(txt, []string{"version=1", "scheme=https", "path=/files", "host=media.example"}) {
		t.Fatalf("port=%d txt=%v err=%v", port, txt, err)
	}
	for _, value := range []string{"ftp://media.example", "https://user:secret@media.example", "https://media.example/?token=secret", "https://media.example/#fragment", "http://media.example:70000"} {
		if _, _, err := endpoint(&net.TCPAddr{Port: 8080}, Options{PublicURL: value}); err == nil {
			t.Fatalf("accepted invalid advertisement %q", value)
		}
	}
}

// Explicit opt-in: uses only a dynamically allocated owned HTTP listener and
// publishes no credentials, file roots or production ports.
func TestOwnedLANAdvertisementRoundTrip(t *testing.T) {
	if os.Getenv("FILEWAY_TEST_DISCOVERY_LAN") != "1" {
		t.Skip("requires owned LAN multicast integration")
	}
	listener, err := net.Listen("tcp", "0.0.0.0:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	publisher, err := Start(listener, Options{BasePath: "/owned"})
	if err != nil {
		t.Fatal(err)
	}
	defer publisher.Close()
	port := listener.Addr().(*net.TCPAddr).Port
	entries := make(chan *mdns.ServiceEntry, 32)
	done := make(chan error, 1)
	go func() {
		done <- mdns.Query(&mdns.QueryParam{Service: ServiceType, Domain: "local", Timeout: 3 * time.Second, Entries: entries})
	}()
	matched := false
	for {
		select {
		case entry := <-entries:
			if entry.Port == port {
				for _, value := range entry.InfoFields {
					if value == "path=/owned" {
						matched = true
					}
				}
			}
		case err := <-done:
			if err != nil {
				t.Fatal(err)
			}
			if !matched {
				t.Fatal("owned Fileway DNS-SD advertisement was not resolved")
			}
			return
		}
	}
}
