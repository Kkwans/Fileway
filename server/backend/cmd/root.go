package cmd

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"runtime"
	"syscall"
	"time"

	"github.com/spf13/afero"
	"github.com/spf13/cobra"
	"github.com/spf13/pflag"
	"github.com/spf13/viper"
	lumberjack "gopkg.in/natefinch/lumberjack.v2"

	"github.com/Kkwans/nas-file-browser/backend/auth"
	"github.com/Kkwans/nas-file-browser/backend/discovery"
	"github.com/Kkwans/nas-file-browser/backend/diskcache"
	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/frontend"
	"github.com/Kkwans/nas-file-browser/backend/hls"
	fbhttp "github.com/Kkwans/nas-file-browser/backend/http"
	"github.com/Kkwans/nas-file-browser/backend/img"
	"github.com/Kkwans/nas-file-browser/backend/settings"
	"github.com/Kkwans/nas-file-browser/backend/storage"
	"github.com/Kkwans/nas-file-browser/backend/users"
)

var (
	flagNamesMigrations = map[string]string{
		"file-mode":                        "fileMode",
		"dir-mode":                         "dirMode",
		"hide-login-button":                "hideLoginButton",
		"create-user-dir":                  "createUserDir",
		"minimum-password-length":          "minimumPasswordLength",
		"socket-perm":                      "socketPerm",
		"disable-thumbnails":               "disableThumbnails",
		"disable-preview-resize":           "disablePreviewResize",
		"disable-exec":                     "disableExec",
		"disable-type-detection-by-header": "disableTypeDetectionByHeader",
		"img-processors":                   "imageProcessors",
		"cache-dir":                        "cacheDir",
		"redis-cache-url":                  "redisCacheUrl",
		"token-expiration-time":            "tokenExpirationTime",
		"baseurl":                          "baseURL",
	}

	warnedFlags = map[string]bool{}
)

// TODO(remove): remove after July 2026.
func migrateFlagNames(_ *pflag.FlagSet, name string) pflag.NormalizedName {
	if newName, ok := flagNamesMigrations[name]; ok {

		if !warnedFlags[name] {
			warnedFlags[name] = true
			log.Printf("DEPRECATION NOTICE: Flag --%s has been deprecated, use --%s instead\n", name, newName)
		}

		name = newName
	}

	return pflag.NormalizedName(name)
}

func init() {
	rootCmd.SilenceUsage = true
	rootCmd.SetGlobalNormalizationFunc(migrateFlagNames)

	cobra.MousetrapHelpText = ""

	rootCmd.SetVersionTemplate("File Browser version {{printf \"%s\" .Version}}\n")

	// Flags available across the whole program
	persistent := rootCmd.PersistentFlags()
	persistent.StringP("config", "c", "", "config file path")
	persistent.StringP("database", "d", "./filebrowser.db", "database path")

	// Runtime flags for the root command
	flags := rootCmd.Flags()
	flags.Bool("noauth", false, "use the noauth auther when using quick setup")
	flags.String("username", "admin", "username for the first user when using quick setup")
	flags.String("password", "", "hashed password for the first user when using quick setup")
	flags.Uint32("socketPerm", 0666, "unix socket file permissions")
	flags.String("cacheDir", "", "file cache directory (disabled if empty)")
	flags.Uint64("cacheMaxBytes", uint64(diskcache.DefaultMaxBytes), "file preview cache limit in bytes")
	flags.String("redisCacheUrl", "", "redis cache URL (for multi-instance deployments), e.g. redis://user:pass@host:port")
	flags.Int("imageProcessors", 1, "image processors count")
	flags.Int("videoPreviewProcessors", 1, "FFmpeg video cover processors count (1-2)")
	flags.String("hlsCacheDir", filepath.Join(os.TempDir(), "nas-file-browser-hls"), "HLS compatibility playback cache directory")
	flags.Uint64("hlsCacheMaxBytes", uint64(hls.DefaultMaxBytes), "HLS compatibility playback cache limit in bytes")
	flags.Int("hlsProcessors", 1, "FFmpeg HLS compatibility playback processors count (1-2)")
	flags.Int("hlsEncodeThreads", 4, "FFmpeg software decoding/encoding threads per job (1-4)")
	flags.Bool("disableDiscovery", false, "disable LAN Fileway DNS-SD discovery")
	flags.String("discoveryURL", "", "LAN reachable URL for discovery behind a reverse proxy or port mapping")
	flags.String("hlsAccelerator", "software", "video accelerator: software or rkmpp (requires Rockchip FFmpeg and devices)")
	addServerFlags(flags)
}

// addServerFlags adds server related flags to the given FlagSet. These flags are available
// in both the root command, config set and config init commands.
func addServerFlags(flags *pflag.FlagSet) {
	defaultRoot := "."
	if runtime.GOOS == "windows" {
		defaultRoot = files.VirtualComputerRoot
	}
	flags.StringP("address", "a", "127.0.0.1", "address to listen on")
	flags.StringP("log", "l", "stdout", "log output")
	flags.StringP("port", "p", "8080", "port to listen on")
	flags.StringP("cert", "t", "", "tls certificate")
	flags.StringP("key", "k", "", "tls key")
	flags.StringP("root", "r", defaultRoot, "root to prepend to relative paths")
	flags.String("socket", "", "socket to listen to (cannot be used with address, port, cert nor key flags)")
	flags.StringP("baseURL", "b", "", "base url")
	flags.String("tokenExpirationTime", "2h", "user session timeout")
	flags.Bool("disableThumbnails", false, "disable image thumbnails")
	flags.Bool("disablePreviewResize", false, "disable resize of image previews")
	flags.Bool("disableExec", true, "disables Command Runner feature")
	flags.Bool("disableTypeDetectionByHeader", false, "disables type detection by reading file headers")
	flags.Bool("disableImageResolutionCalc", false, "disables image resolution calculation by reading image files")
}

var rootCmd = &cobra.Command{
	Use:   "filebrowser",
	Short: "A stylish web-based file browser",
	Long: `File Browser CLI lets you create the database to use with File Browser,
manage your users and all the configurations without accessing the
web interface.

If you've never run File Browser, you'll need to have a database for
it. Don't worry: you don't need to setup a separate database server.
We're using Bolt DB which is a single file database and all managed
by ourselves.

For this command, all flags are available as environmental variables,
except for "--config", which specifies the configuration file to use.
The environment variables are prefixed by "FB_" followed by the flag name in
UPPER_SNAKE_CASE. For example, the flag "--disablePreviewResize" is available
as FB_DISABLE_PREVIEW_RESIZE.

If "--config" is not specified, File Browser will look for a configuration
file named .filebrowser.{json, toml, yaml, yml} in the following directories:

- ./
- $HOME/
- /etc/filebrowser/

**Note:** Only the options listed below can be set via the config file or
environment variables. Other configuration options live exclusively in the
database and so they must be set by the "config set" or "config
import" commands.

The precedence of the configuration values are as follows:

- Flags
- Environment variables
- Configuration file
- Database values
- Defaults

Also, if the database path doesn't exist, File Browser will enter into
the quick setup mode and a new database will be bootstrapped and a new
user created with the credentials from options "username" and "password".`,
	RunE: withViperAndStore(func(_ *cobra.Command, _ []string, v *viper.Viper, st *store) error {
		if !st.databaseExisted {
			err := quickSetup(v, st.Storage)
			if err != nil {
				return err
			}
		}

		// build img service
		imgWorkersCount := v.GetInt("imageProcessors")
		if imgWorkersCount < 1 {
			return errors.New("image resize workers count could not be < 1")
		}
		imageService := img.New(imgWorkersCount)
		videoPreviewWorkers := v.GetInt("videoPreviewProcessors")
		if videoPreviewWorkers < 1 || videoPreviewWorkers > 2 {
			return errors.New("FFmpeg video cover processors count must be between 1 and 2")
		}
		hlsWorkers := v.GetInt("hlsProcessors")
		if hlsWorkers < 1 || hlsWorkers > 2 {
			return errors.New("FFmpeg HLS processors count must be between 1 and 2")
		}
		hlsMaxBytes := v.GetUint64("hlsCacheMaxBytes")
		if hlsMaxBytes == 0 || hlsMaxBytes > uint64(^uint64(0)>>1) {
			return errors.New("HLS cache limit must be a positive int64 byte count")
		}
		hlsService, err := hls.New(hls.Config{
			CacheDir: v.GetString("hlsCacheDir"), MaxBytes: int64(hlsMaxBytes), Workers: hlsWorkers,
			EncodeThreads: v.GetInt("hlsEncodeThreads"),
			Accelerator:   v.GetString("hlsAccelerator"),
		})
		if err != nil {
			return fmt.Errorf("failed to initialize HLS compatibility playback: %w", err)
		}

		var fileCache diskcache.Interface = diskcache.NewNoOp()
		cacheDir := v.GetString("cacheDir")
		if cacheDir != "" {
			cacheMaxBytes := v.GetUint64("cacheMaxBytes")
			if cacheMaxBytes == 0 || cacheMaxBytes > uint64(^uint64(0)>>1) {
				return errors.New("file preview cache limit must be a positive int64 byte count")
			}
			if err := os.MkdirAll(cacheDir, 0700); err != nil {
				return fmt.Errorf("can't make directory %s: %w", cacheDir, err)
			}
			fileCache, err = diskcache.NewBounded(afero.NewOsFs(), cacheDir, int64(cacheMaxBytes))
			if err != nil {
				return fmt.Errorf("can't initialize file preview cache: %w", err)
			}
		}

		redisCacheURL := v.GetString("redisCacheUrl")
		uploadCache, err := fbhttp.NewUploadCache(redisCacheURL)
		if err != nil {
			return fmt.Errorf("failed to initialize upload cache: %w", err)
		}
		defer uploadCache.Close()

		server, err := getServerSettings(v, st.Storage)
		if err != nil {
			return err
		}
		setupLog(server.Log)

		if !files.IsVirtualComputerRoot(server.Root) {
			root, err := filepath.Abs(server.Root)
			if err != nil {
				return err
			}
			server.Root = root
		}

		adr := server.Address + ":" + server.Port

		var listener net.Listener

		switch {
		case server.Socket != "":
			listener, err = net.Listen("unix", server.Socket)
			if err != nil {
				return err
			}
			socketPerm := v.GetUint32("socketPerm")
			err = os.Chmod(server.Socket, os.FileMode(socketPerm))
			if err != nil {
				return err
			}
		case server.TLSKey != "" && server.TLSCert != "":
			cer, err := tls.LoadX509KeyPair(server.TLSCert, server.TLSKey)
			if err != nil {
				return err
			}
			listener, err = tls.Listen("tcp", adr, &tls.Config{
				MinVersion:   tls.VersionTLS12,
				Certificates: []tls.Certificate{cer}},
			)
			if err != nil {
				return err
			}
		default:
			listener, err = net.Listen("tcp", adr)
			if err != nil {
				return err
			}
		}

		assetsFs, err := fs.Sub(frontend.Assets(), "dist")
		if err != nil {
			panic(err)
		}

		handler, err := fbhttp.NewHandler(imageService, fileCache, uploadCache, st.Storage, server, assetsFs, videoPreviewWorkers, hlsService)
		if err != nil {
			return err
		}
		if closer, ok := handler.(io.Closer); ok {
			defer func() { _ = closer.Close() }()
		}
		uploadContext, stopUploads := context.WithCancel(context.Background())
		defer stopUploads()
		fbhttp.StartUploadCleanup(uploadContext, st.Storage, server)

		defer func() { _ = listener.Close() }()

		if !v.GetBool("disableDiscovery") {
			advertisement, discoveryErr := discovery.Start(listener, discovery.Options{BasePath: server.BaseURL, TLS: server.TLSKey != "" && server.TLSCert != "", PublicURL: v.GetString("discoveryURL")})
			if discoveryErr != nil {
				log.Printf("LAN discovery unavailable: %v", discoveryErr)
			} else {
				defer advertisement.Close()
			}
		}
		log.Println("Listening on", listener.Addr().String())
		srv := &http.Server{
			Handler:           handler,
			ReadHeaderTimeout: 60 * time.Second,
		}

		go func() {
			if err := srv.Serve(listener); !errors.Is(err, http.ErrServerClosed) {
				log.Fatalf("HTTP server error: %v", err)
			}

			log.Println("Stopped serving new connections.")
		}()

		sigc := make(chan os.Signal, 1)
		signal.Notify(sigc,
			os.Interrupt,
			syscall.SIGHUP,
			syscall.SIGINT,
			syscall.SIGTERM,
			syscall.SIGQUIT,
		)
		sig := <-sigc
		log.Println("Got signal:", sig)

		shutdownCtx, shutdownRelease := context.WithTimeout(context.Background(), 10*time.Second)
		defer shutdownRelease()

		if err := srv.Shutdown(shutdownCtx); err != nil {
			log.Fatalf("HTTP shutdown error: %v", err)
		}
		log.Println("Graceful shutdown complete.")

		return nil
	}, storeOptions{allowsNoDatabase: true}),
}

func getServerSettings(v *viper.Viper, st *storage.Storage) (*settings.Server, error) {
	server, err := st.Settings.GetServer()
	if err != nil {
		return nil, err
	}

	isSocketSet := false
	isAddrSet := false

	if v.IsSet("address") {
		server.Address = v.GetString("address")
		isAddrSet = true
	}

	if v.IsSet("log") {
		server.Log = v.GetString("log")
	}

	if v.IsSet("port") {
		server.Port = v.GetString("port")
		isAddrSet = true
	}

	if v.IsSet("cert") {
		server.TLSCert = v.GetString("cert")
		isAddrSet = true
	}

	if v.IsSet("key") {
		server.TLSKey = v.GetString("key")
		isAddrSet = true
	}

	if v.IsSet("root") {
		server.Root = v.GetString("root")
	}

	if v.IsSet("socket") {
		server.Socket = v.GetString("socket")
		isSocketSet = true
	}

	if v.IsSet("baseURL") {
		server.BaseURL = v.GetString("baseURL")
		// TODO(remove): remove after July 2026.
	} else if v := os.Getenv("FB_BASEURL"); v != "" {
		log.Println("DEPRECATION NOTICE: Environment variable FB_BASEURL has been deprecated, use FB_BASE_URL instead")
		server.BaseURL = v
	}

	if v.IsSet("tokenExpirationTime") {
		server.TokenExpirationTime = v.GetString("tokenExpirationTime")
	}

	if v.IsSet("disableThumbnails") {
		server.EnableThumbnails = !v.GetBool("disableThumbnails")
	}

	if v.IsSet("disablePreviewResize") {
		server.ResizePreview = !v.GetBool("disablePreviewResize")
	}

	if v.IsSet("disableTypeDetectionByHeader") {
		server.TypeDetectionByHeader = !v.GetBool("disableTypeDetectionByHeader")
	}

	if v.IsSet("disableImageResolutionCalc") {
		server.ImageResolutionCal = !v.GetBool("disableImageResolutionCalc")
	}

	if v.IsSet("disableExec") {
		server.EnableExec = !v.GetBool("disableExec")
	}

	if isAddrSet && isSocketSet {
		return nil, errors.New("--socket flag cannot be used with --address, --port, --key nor --cert")
	}

	// Do not use saved Socket if address was manually set.
	if isAddrSet && server.Socket != "" {
		server.Socket = ""
	}

	if server.EnableExec {
		log.Println("WARNING: Command Runner feature enabled!")
		log.Println("WARNING: This feature has known security vulnerabilities and should not")
		log.Println("WARNING: you fully understand the risks involved. For more information")
		log.Println("WARNING: read https://github.com/filebrowser/filebrowser/issues/5199")
	}

	return server, nil
}

func setupLog(logMethod string) {
	switch logMethod {
	case "stdout":
		log.SetOutput(os.Stdout)
	case "stderr":
		log.SetOutput(os.Stderr)
	case "":
		log.SetOutput(io.Discard)
	default:
		log.SetOutput(&lumberjack.Logger{
			Filename:   logMethod,
			MaxSize:    100,
			MaxAge:     14,
			MaxBackups: 10,
		})
	}
}

func quickSetup(v *viper.Viper, s *storage.Storage) error {
	log.Println("Performing quick setup")

	set := &settings.Settings{
		Key:                   generateKey(),
		Signup:                false,
		HideLoginButton:       true,
		CreateUserDir:         false,
		MinimumPasswordLength: settings.DefaultMinimumPasswordLength,
		UserHomeBasePath:      settings.DefaultUsersHomeBasePath,
		Defaults: settings.UserDefaults{
			Scope:                 ".",
			Locale:                "en",
			SingleClick:           false,
			RedirectAfterCopyMove: true,
			AceEditorTheme:        v.GetString("defaults.aceEditorTheme"),
			Perm: users.Permissions{
				Admin:    false,
				Execute:  true,
				Create:   true,
				Rename:   true,
				Modify:   true,
				Delete:   true,
				Share:    true,
				Download: true,
			},
		},
		AuthMethod: "",
		Branding:   settings.Branding{},
		Tus: settings.Tus{
			ChunkSize:  settings.DefaultTusChunkSize,
			RetryCount: settings.DefaultTusRetryCount,
		},
		Commands: nil,
		Shell:    nil,
		Rules:    nil,
	}

	var err error
	if v.GetBool("noauth") {
		set.AuthMethod = auth.MethodNoAuth
		err = s.Auth.Save(&auth.NoAuth{})
	} else {
		set.AuthMethod = auth.MethodJSONAuth
		err = s.Auth.Save(&auth.JSONAuth{})
	}
	if err != nil {
		return err
	}

	err = s.Settings.Save(set)
	if err != nil {
		return err
	}

	ser := &settings.Server{
		BaseURL:               v.GetString("baseURL"),
		Port:                  v.GetString("port"),
		Log:                   v.GetString("log"),
		TLSKey:                v.GetString("key"),
		TLSCert:               v.GetString("cert"),
		Address:               v.GetString("address"),
		Root:                  v.GetString("root"),
		TokenExpirationTime:   v.GetString("tokenExpirationTime"),
		EnableThumbnails:      !v.GetBool("disableThumbnails"),
		ResizePreview:         !v.GetBool("disablePreviewResize"),
		EnableExec:            !v.GetBool("disableExec"),
		TypeDetectionByHeader: !v.GetBool("disableTypeDetectionByHeader"),
		ImageResolutionCal:    !v.GetBool("disableImageResolutionCalc"),
	}

	err = s.Settings.SaveServer(ser)
	if err != nil {
		return err
	}

	username := v.GetString("username")
	password := v.GetString("password")

	if password == "" {
		var pwd string
		pwd, err = users.RandomPwd(set.MinimumPasswordLength)
		if err != nil {
			return err
		}

		log.Printf("User '%s' initialized with randomly generated password: %s\n", username, pwd)
		password, err = users.ValidateAndHashPwd(pwd, set.MinimumPasswordLength)
		if err != nil {
			return err
		}
	} else {
		log.Printf("User '%s' initialize wth user-provided password\n", username)
	}

	if username == "" || password == "" {
		log.Fatal("username and password cannot be empty during quick setup")
	}

	user := &users.User{
		Username:     username,
		Password:     password,
		LockPassword: false,
	}

	set.Defaults.Apply(user)
	user.Perm.Admin = true

	return s.Users.Save(user)
}
