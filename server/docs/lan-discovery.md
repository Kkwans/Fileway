# LAN service discovery

Fileway advertises `_fileway._tcp.local.` over DNS-SD/mDNS when its HTTP listener is reachable on a multicast LAN interface. Android TV browses/resolves it using Android NSD (API28 compatible), then asks the user to select the server and sign in. Normal onboarding does not ask for a URL. Manual addition remains a recovery path for restricted networks and older servers.

TXT records: `version=1`, `scheme=http|https`, `path=<base URL path>`. An optional `host=<configured public hostname>` supports a reverse proxy/TLS identity. SRV carries the actual advertised port; A/AAAA are scoped to eligible listening interfaces. Records never contain credentials, account names, file roots or permissions. Discovery is a candidate list, never authentication or authority to move saved passwords to another origin.

Runtime options (also accepted in existing config/environment handling):

- `--disableDiscovery=true`: stop publishing DNS-SD without changing HTTP.
- `--discoveryURL=https://media.example:9443/fileway`: advertise the LAN URL of a reverse proxy or a container port mapping. This is an administrator setting, not a field the TV user must fill.

mDNS requires UDP5353 multicast reachability. An isolated Docker bridge cannot advertise directly to the physical LAN; deployment must provide host networking or an mDNS relay/advertiser on the LAN. Setting discoveryURL alone does not bridge multicast. Do not expose loopback-only listeners by default. Failure to publish is logged and does not stop HTTP. Deployment/network changes remain separate authorized operations.

The TV stores username/account metadata in its private Room database, and token/password in the existing Android Keystore AES-GCM vault excluded from backup. App relaunch first restores/renews the token, then uses the saved password after authentication expiry. Explicit sign-out removes credentials; rejected passwords are removed without erasing a newer saved password. Network failures preserve saved credentials and provide reconnect, without forcing credential entry. Uninstalling or clearing app data removes local storage.

Validation distinguishes endpoint unit tests, an owned NAS multicast publish/query round trip, Android9 NSD register/discover/resolve, actual login recovery, and real-device LAN multicast. Passing one does not prove all network topologies.
