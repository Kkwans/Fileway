//go:generate go-enum --sql --marshal --names --file $GOFILE
package fbhttp

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"image"
	"io"
	"log"
	"net/http"
	"sync"
	"time"

	"github.com/gorilla/mux"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/img"
)

/*
ENUM(
thumb
big
)
*/
type PreviewSize int

type ImgService interface {
	FormatFromExtension(ext string) (img.Format, error)
	Resize(ctx context.Context, in io.Reader, width, height int, out io.Writer, options ...img.Option) error
}

type FileCache interface {
	Store(ctx context.Context, key string, value []byte) error
	Load(ctx context.Context, key string) ([]byte, bool, error)
	Delete(ctx context.Context, key string) error
}

var errPreviewCoolingDown = errors.New("preview generation is cooling down after a recent failure")

const previewFailureEntries = 256
const previewCacheControl = "private, max-age=86400"

type previewFlight struct {
	ctx       context.Context
	cancel    context.CancelFunc
	done      chan struct{}
	keepAlive bool
	waiters   int
	data      []byte
	err       error
}

type previewCoordinator struct {
	sync.Mutex
	flights  map[string]*previewFlight
	failures map[string]time.Time
	cooldown time.Duration
}

func newPreviewCoordinator() *previewCoordinator {
	return &previewCoordinator{
		flights: make(map[string]*previewFlight), failures: make(map[string]time.Time),
		cooldown: 15 * time.Second,
	}
}

func (c *previewCoordinator) Do(ctx context.Context, key string, work func(context.Context) ([]byte, error)) ([]byte, error) {
	return c.do(ctx, key, false, work)
}

// DoKeepAlive shares the same in-flight work as regular callers, but keeps the
// producer alive when the initiating request is canceled.  This is used by
// best-effort media warmups: a placeholder may be detached while the larger
// preview is still useful to the next request.
func (c *previewCoordinator) DoKeepAlive(ctx context.Context, key string, work func(context.Context) ([]byte, error)) ([]byte, error) {
	return c.do(ctx, key, true, work)
}

func (c *previewCoordinator) do(ctx context.Context, key string, keepAlive bool, work func(context.Context) ([]byte, error)) ([]byte, error) {
	c.Lock()
	c.pruneFailures(time.Now())
	if until, failed := c.failures[key]; failed {
		if time.Now().Before(until) {
			c.Unlock()
			return nil, errPreviewCoolingDown
		}
		delete(c.failures, key)
	}

	flight := c.flights[key]
	if flight == nil {
		flightCtx, cancel := context.WithCancel(context.Background())
		flight = &previewFlight{
			ctx: flightCtx, cancel: cancel, done: make(chan struct{}),
			keepAlive: keepAlive,
		}
		c.flights[key] = flight
		go func() {
			flight.data, flight.err = work(flight.ctx)
			c.Lock()
			if flight.err != nil && !errors.Is(flight.err, context.Canceled) {
				c.pruneFailures(time.Now())
				if _, exists := c.failures[key]; !exists && len(c.failures) >= previewFailureEntries {
					c.evictEarliestFailure()
				}
				c.failures[key] = time.Now().Add(c.cooldown)
			}
			delete(c.flights, key)
			close(flight.done)
			c.Unlock()
		}()
	}
	flight.waiters++
	c.Unlock()

	select {
	case <-flight.done:
		c.releaseWaiter(flight, false)
		return append([]byte(nil), flight.data...), flight.err
	case <-ctx.Done():
		c.releaseWaiter(flight, true)
		return nil, context.Cause(ctx)
	}
}

func (c *previewCoordinator) pruneFailures(now time.Time) {
	for key, until := range c.failures {
		if !now.Before(until) {
			delete(c.failures, key)
		}
	}
}

func (c *previewCoordinator) evictEarliestFailure() {
	var earliestKey string
	var earliest time.Time
	for key, until := range c.failures {
		if earliestKey == "" || until.Before(earliest) {
			earliestKey = key
			earliest = until
		}
	}
	if earliestKey != "" {
		delete(c.failures, earliestKey)
	}
}

func (c *previewCoordinator) releaseWaiter(flight *previewFlight, canceled bool) {
	c.Lock()
	defer c.Unlock()
	flight.waiters--
	if canceled && flight.waiters == 0 && !flight.keepAlive {
		flight.cancel()
	}
}

func previewHandler(imgSvc ImgService, fileCache FileCache, enableThumbnails, resizePreview bool, videoPreviewWorkers int) handleFunc {
	coordinator := newPreviewCoordinator()
	ffmpeg := newFFmpegPreviewService(videoPreviewWorkers)
	ffmpegImage := newFFmpegImagePreviewService(1)
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		if !d.user.Perm.Download {
			return http.StatusAccepted, nil
		}
		vars := mux.Vars(r)

		previewSize, err := ParsePreviewSize(vars["size"])
		if err != nil {
			return http.StatusBadRequest, err
		}
		contain := previewSize == PreviewSizeThumb && r.URL.Query().Get("fit") == "contain"
		if contain && !enableThumbnails {
			return http.StatusNotImplemented, fmt.Errorf("缩略图功能未启用")
		}

		file, err := files.NewFileInfo(&files.FileOptions{
			Fs:            d.user.Fs,
			Path:          "/" + vars["path"],
			Modify:        d.user.Perm.Modify,
			Expand:        true,
			SkipSubtitles: true,
			ReadHeader:    d.server.TypeDetectionByHeader,
			Checker:       d,
		})
		if err != nil {
			return errToStatus(err), err
		}

		setContentDisposition(w, r, file)

		switch file.Type {
		case "image":
			if contain {
				return handleContainedImagePreview(w, r, imgSvc, ffmpegImage, fileCache, coordinator, file, enableThumbnails)
			}
			return handleImagePreview(w, r, imgSvc, ffmpegImage, fileCache, coordinator, file, previewSize, enableThumbnails, resizePreview)
		case "video":
			return handleVideoPreview(w, r, fileCache, coordinator, ffmpeg, file, previewSize, contain)
		default:
			return http.StatusNotImplemented, fmt.Errorf("不支持预览 %s 类型的文件", file.Type)
		}
	})
}

func containedPreviewCacheKey(file *files.FileInfo) string {
	return fmt.Sprintf("preview:v4:contain512:%s:%d:%d", file.RealPath(), file.ModTime.UnixNano(), file.Size)
}

func handleContainedImagePreview(
	w http.ResponseWriter,
	r *http.Request,
	imgSvc ImgService,
	ffmpegImage *ffmpegImagePreviewService,
	fileCache FileCache,
	coordinator *previewCoordinator,
	file *files.FileInfo,
	enabled bool,
) (int, error) {
	if !enabled {
		return http.StatusNotImplemented, fmt.Errorf("缩略图功能未启用")
	}
	format, err := imgSvc.FormatFromExtension(file.Extension)
	if err != nil || format == img.FormatGif {
		return http.StatusNotImplemented, fmt.Errorf("此图片格式不支持等比例缩略图")
	}
	outputFormat := img.FormatJpeg
	if format == img.FormatPng {
		outputFormat = img.FormatPng
	}
	key := containedPreviewCacheKey(file)
	preview, ok, err := loadPreviewCache(r.Context(), fileCache, key)
	if err != nil {
		return errToStatus(err), err
	}
	if !ok {
		preview, err = coordinator.Do(r.Context(), key, func(ctx context.Context) ([]byte, error) {
			if cached, exists, loadErr := loadPreviewCache(ctx, fileCache, key); loadErr != nil || exists {
				return cached, loadErr
			}
			var generated []byte
			var generateErr error
			if ffmpegImage != nil && shouldUseFFmpegImagePreview(file, PreviewSizeThumb) {
				generated, generateErr = ffmpegImage.createContained(ctx, file)
			}
			if generateErr != nil || len(generated) == 0 {
				fd, openErr := files.OpenPublicResource(file.Fs, file.Path)
				if openErr != nil {
					return nil, openErr
				}
				defer func() { _ = fd.Close() }()
				buffer := &bytes.Buffer{}
				generateErr = imgSvc.Resize(ctx, fd, 512, 512, buffer,
					img.WithMode(img.ResizeModeFit), img.WithQuality(img.QualityMedium), img.WithFormat(outputFormat))
				generated = buffer.Bytes()
			}
			if generateErr != nil {
				return nil, generateErr
			}
			storePreviewCache(ctx, fileCache, key, generated)
			return generated, nil
		})
	}
	if err != nil {
		return errToStatus(err), err
	}
	w.Header().Set("Cache-Control", previewCacheControl)
	if outputFormat == img.FormatPng {
		w.Header().Set("Content-Type", "image/png")
	} else {
		w.Header().Set("Content-Type", "image/jpeg")
	}
	http.ServeContent(w, r, file.Name, file.ModTime, bytes.NewReader(preview))
	return 0, nil
}

func handleImagePreview(
	w http.ResponseWriter,
	r *http.Request,
	imgSvc ImgService,
	ffmpegImage *ffmpegImagePreviewService,
	fileCache FileCache,
	coordinator *previewCoordinator,
	file *files.FileInfo,
	previewSize PreviewSize,
	enableThumbnails, resizePreview bool,
) (int, error) {
	if (previewSize == PreviewSizeBig && !resizePreview) ||
		(previewSize == PreviewSizeThumb && !enableThumbnails) {
		return rawFileHandler(w, r, file)
	}

	format, err := imgSvc.FormatFromExtension(file.Extension)
	// Unsupported extensions directly return the raw data
	if errors.Is(err, img.ErrUnsupportedFormat) || format == img.FormatGif {
		return rawFileHandler(w, r, file)
	}
	if err != nil {
		return errToStatus(err), err
	}

	cacheKey := previewCacheKey(file, previewSize)
	warmLargeJPEG := previewSize == PreviewSizeThumb && shouldWarmLargeJPEGPreview(r, file)
	resizedImage, ok, err := loadPreviewCache(r.Context(), fileCache, cacheKey)
	if err != nil {
		return errToStatus(err), err
	}
	// A warm request must run even when a small listing thumbnail already
	// exists. Its response is the reusable large preview, while the exact
	// thumbnail remains available from the regular cache key.
	if warmLargeJPEG {
		ok = false
	}
	if !ok {
		coordinatorKey := cacheKey
		keepAlive := false
		if warmLargeJPEG {
			// Warm requests and the regular big-preview request must share one
			// flight.  Otherwise a cold open can queue two full JPEG decodes
			// behind the global FFmpeg worker, which is exactly the multi-second
			// stall this path is meant to avoid.
			coordinatorKey = previewCacheKey(file, PreviewSizeBig)
			keepAlive = true
		}
		if keepAlive {
			resizedImage, err = coordinator.DoKeepAlive(r.Context(), coordinatorKey, func(ctx context.Context) ([]byte, error) {
				if cached, exists, loadErr := loadPreviewCache(ctx, fileCache, coordinatorKey); loadErr != nil {
					return nil, loadErr
				} else if exists {
					return cached, nil
				}
				return createImagePreview(ctx, imgSvc, ffmpegImage, fileCache, file, PreviewSizeBig)
			})
			if err == nil {
				resizedImage, err = createLargeJPEGThumbnailFromBigPreview(r.Context(), imgSvc, fileCache, file, resizedImage)
			}
		} else {
			resizedImage, err = coordinator.Do(r.Context(), coordinatorKey, func(ctx context.Context) ([]byte, error) {
				if cached, exists, loadErr := loadPreviewCache(ctx, fileCache, cacheKey); loadErr != nil {
					return nil, loadErr
				} else if exists {
					return cached, nil
				}
				return createImagePreview(ctx, imgSvc, ffmpegImage, fileCache, file, previewSize)
			})
		}
		if err != nil {
			if errors.Is(err, errPreviewCoolingDown) {
				return http.StatusServiceUnavailable, fmt.Errorf("缩略图生成暂时冷却，请稍后重试")
			}
			return errToStatus(err), err
		}
	}

	// The URL carries the file's mtime and size identity, so a private browser
	// cache can reuse the generated preview without serving stale content after
	// an in-place file update.
	w.Header().Set("Cache-Control", previewCacheControl)
	if previewSize == PreviewSizeThumb {
		w.Header().Set("Content-Type", "image/jpeg")
	}
	http.ServeContent(w, r, file.Name, file.ModTime, bytes.NewReader(resizedImage))

	return 0, nil
}

func createImagePreview(
	ctx context.Context,
	imgSvc ImgService,
	ffmpegImage *ffmpegImagePreviewService,
	fileCache FileCache,
	file *files.FileInfo,
	previewSize PreviewSize,
) ([]byte, error) {
	if shouldPreferNativeImagePreview(file, previewSize) {
		// The Go pipeline can reuse an EXIF-embedded JPEG thumbnail without
		// decoding the full source. Prefer it for listing thumbnails and
		// moderate-size viewer previews; keep FFmpeg as a safe fallback for
		// malformed metadata or JPEGs without a usable embedded thumbnail.
		generated, err := createPreview(ctx, imgSvc, fileCache, file, previewSize)
		if err == nil {
			return generated, nil
		}
		if ctxErr := context.Cause(ctx); ctxErr != nil {
			return nil, ctxErr
		}
		log.Printf("WARNING: 原生 JPEG 缩略图失败，回退 FFmpeg: %v", err)
	}
	if ffmpegImage != nil && shouldUseFFmpegImagePreview(file, previewSize) {
		generated, err := ffmpegImage.create(ctx, file, previewSize)
		if err == nil {
			storePreviewCache(ctx, fileCache, previewCacheKey(file, previewSize), generated)
			return generated, nil
		}
		if ctxErr := context.Cause(ctx); ctxErr != nil {
			return nil, ctxErr
		}
		log.Printf("WARNING: FFmpeg 图片预览失败，回退 Go 解码: %v", err)
	}
	return createPreview(ctx, imgSvc, fileCache, file, previewSize)
}

func createPreview(ctx context.Context, imgSvc ImgService, fileCache FileCache,
	file *files.FileInfo, previewSize PreviewSize) ([]byte, error) {
	fd, err := files.OpenPublicResource(file.Fs, file.Path)
	if err != nil {
		return nil, err
	}
	defer func() { _ = fd.Close() }()

	var (
		width   int
		height  int
		options []img.Option
	)

	switch previewSize {
	case PreviewSizeBig:
		width = 1080
		height = 1080
		options = append(options, img.WithMode(img.ResizeModeFit), img.WithQuality(img.QualityMedium))
	case PreviewSizeThumb:
		width = 256
		height = 256
		options = append(options, img.WithMode(img.ResizeModeFill), img.WithQuality(img.QualityLow), img.WithFormat(img.FormatJpeg))
	default:
		return nil, img.ErrUnsupportedFormat
	}

	buf := &bytes.Buffer{}
	if err := imgSvc.Resize(ctx, fd, width, height, buf, options...); err != nil {
		return nil, err
	}
	// A preview is still useful when the disposable cache is temporarily
	// unavailable. Cache failures must not turn a successful resize into a 5xx.
	storePreviewCache(ctx, fileCache, previewCacheKey(file, previewSize), buf.Bytes())

	return buf.Bytes(), nil
}

func loadPreviewCache(ctx context.Context, fileCache FileCache, key string) ([]byte, bool, error) {
	value, exists, err := fileCache.Load(ctx, key)
	if err != nil {
		if ctxErr := context.Cause(ctx); ctxErr != nil {
			return nil, false, ctxErr
		}
		log.Printf("WARNING: 读取预览缓存失败，按未命中处理: %v", err)
		return nil, false, nil
	}
	if !exists {
		return nil, false, nil
	}
	if validCachedPreview(value) {
		return value, true, nil
	}
	if deleteErr := fileCache.Delete(ctx, key); deleteErr != nil && context.Cause(ctx) == nil {
		log.Printf("WARNING: 清理损坏的预览缓存失败: %v", deleteErr)
	}
	return nil, false, nil
}

func storePreviewCache(ctx context.Context, fileCache FileCache, key string, value []byte) {
	if err := fileCache.Store(ctx, key, value); err != nil && context.Cause(ctx) == nil {
		log.Printf("WARNING: 写入预览缓存失败，本次预览仍继续返回: %v", err)
	}
}

func previewCacheKey(f *files.FileInfo, previewSize PreviewSize) string {
	return fmt.Sprintf("preview:v3:%s:%d:%d:%s", f.RealPath(), f.ModTime.UnixNano(), f.Size, previewSize.String())
}

func validCachedPreview(value []byte) bool {
	if len(value) == 0 {
		return false
	}
	_, _, err := image.DecodeConfig(bytes.NewReader(value))
	return err == nil
}
