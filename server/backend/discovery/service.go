// Package discovery advertises Fileway's login endpoint over local DNS-SD.
// Advertisements contain no account, token, root path or permission information.
package discovery

import (
	"fmt"
	"io"
	"net"
	"net/url"
	"os"
	"strconv"
	"strings"

	"github.com/hashicorp/mdns"
)

const ServiceType = "_fileway._tcp"

type Options struct {
	BasePath string
	TLS      bool
	// PublicURL is optional for a LAN reverse proxy or a mapped container port.
	// Multicast still needs to reach the LAN (host networking or an mDNS relay).
	PublicURL string
}

type group []*mdns.Server

func (g group) Close() error {
	for _, server := range g {
		_ = server.Shutdown()
	}
	return nil
}

func endpoint(address *net.TCPAddr, options Options) (int, []string, error) {
	scheme := "http"
	if options.TLS {
		scheme = "https"
	}
	port, base, host := address.Port, options.BasePath, ""
	if options.PublicURL != "" {
		target, err := url.Parse(options.PublicURL)
		if err != nil || (target.Scheme != "http" && target.Scheme != "https") || target.Hostname() == "" || target.User != nil || target.RawQuery != "" || target.Fragment != "" {
			return 0, nil, fmt.Errorf("discoveryURL must be an HTTP(S) origin and optional base path")
		}
		scheme, base, host = target.Scheme, target.EscapedPath(), target.Hostname()
		port = 80
		if scheme == "https" {
			port = 443
		}
		if target.Port() != "" {
			port, err = strconv.Atoi(target.Port())
			if err != nil {
				return 0, nil, err
			}
		}
	}
	if port < 1 || port > 65535 || len(base) > 160 || (base != "" && !strings.HasPrefix(base, "/")) || strings.ContainsAny(base, "?#\r\n") {
		return 0, nil, fmt.Errorf("invalid LAN discovery endpoint")
	}
	// An empty base path is represented as /; clients append API paths beneath it.
	if base == "" {
		base = "/"
	}
	txt := []string{"version=1", "scheme=" + scheme, "path=" + base}
	if host != "" {
		if len(host) > 200 {
			return 0, nil, fmt.Errorf("discovery hostname is too long")
		}
		txt = append(txt, "host="+host)
	}
	return port, txt, nil
}

// Start binds responders to eligible LAN interfaces, avoiding loopback-only
// endpoints and addresses from unrelated interfaces. Failure is non-fatal to HTTP.
func Start(listener net.Listener, options Options) (io.Closer, error) {
	address, ok := listener.Addr().(*net.TCPAddr)
	if !ok {
		return group{}, nil
	}
	port, txt, err := endpoint(address, options)
	if err != nil {
		return nil, err
	}
	if address.IP.IsLoopback() && options.PublicURL == "" {
		return group{}, nil
	}
	hostname, err := os.Hostname()
	if err != nil {
		return nil, err
	}
	hostname = strings.Map(func(r rune) rune {
		if r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' || r == '-' {
			return r
		}
		return '-'
	}, hostname)
	if len(hostname) > 32 {
		hostname = hostname[:32]
	}
	if hostname == "" {
		hostname = "server"
	}
	interfaces, err := net.Interfaces()
	if err != nil {
		return nil, err
	}
	var servers group
	for _, iface := range interfaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 || iface.Flags&net.FlagMulticast == 0 {
			continue
		}
		addresses, err := iface.Addrs()
		if err != nil {
			continue
		}
		var ips []net.IP
		for _, value := range addresses {
			ip, _, err := net.ParseCIDR(value.String())
			if err != nil || ip.IsLoopback() || ip.IsLinkLocalUnicast() || ip.IsUnspecified() {
				continue
			}
			if options.PublicURL == "" && !address.IP.IsUnspecified() && !address.IP.Equal(ip) {
				continue
			}
			ips = append(ips, ip)
		}
		if len(ips) == 0 {
			continue
		}
		label := fmt.Sprintf("Fileway-%s-%d", hostname, port)
		zone, err := mdns.NewMDNSService(label, ServiceType, "local.", hostname+".local.", port, ips, txt)
		if err != nil {
			continue
		}
		responder, err := mdns.NewServer(&mdns.Config{Zone: zone, Iface: &iface})
		if err == nil {
			servers = append(servers, responder)
		}
	}
	if len(servers) == 0 {
		return nil, fmt.Errorf("no reachable multicast LAN interface")
	}
	return servers, nil
}
