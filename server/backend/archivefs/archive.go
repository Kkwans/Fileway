package archivefs

import (
	"context"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"math"
	"os"
	"path"
	"runtime"
	"sort"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/Kkwans/nas-file-browser/backend/pathmeta"
	"github.com/mholt/archives"
	"github.com/spf13/afero"
)

const (
	DefaultMaxEntries      = 10_000
	DefaultMaxSelected     = 500
	DefaultMaxFileBytes    = int64(8 << 30)
	DefaultMaxExtractBytes = int64(20 << 30)
	maxBlockedDetails      = 100
	maxSkippedDetails      = 100
	temporaryExtractPrefix = ".nfb-extract-"
)

var (
	ErrUnsupportedFormat = errors.New("unsupported archive format")
	ErrUnsafeEntry       = errors.New("unsafe archive entry")
	ErrLimitExceeded     = errors.New("archive safety limit exceeded")
	ErrArchiveChanged    = errors.New("archive changed before extraction")
	ErrInvalidArchive    = errors.New("invalid or damaged archive")
)

type Checker interface {
	Check(path string) bool
}

type Limits struct {
	MaxEntries      int
	MaxSelected     int
	MaxFileBytes    int64
	MaxExtractBytes int64
}

func DefaultLimits() Limits {
	return Limits{
		MaxEntries:      DefaultMaxEntries,
		MaxSelected:     DefaultMaxSelected,
		MaxFileBytes:    DefaultMaxFileBytes,
		MaxExtractBytes: DefaultMaxExtractBytes,
	}
}

func (limits Limits) normalized() Limits {
	defaults := DefaultLimits()
	if limits.MaxEntries <= 0 {
		limits.MaxEntries = defaults.MaxEntries
	}
	if limits.MaxSelected <= 0 {
		limits.MaxSelected = defaults.MaxSelected
	}
	if limits.MaxFileBytes <= 0 {
		limits.MaxFileBytes = defaults.MaxFileBytes
	}
	if limits.MaxExtractBytes <= 0 {
		limits.MaxExtractBytes = defaults.MaxExtractBytes
	}
	return limits
}

type Entry struct {
	Path           string `json:"path"`
	Name           string `json:"name"`
	IsDir          bool   `json:"isDir"`
	Size           int64  `json:"size"`
	Modified       int64  `json:"modified"`
	PathUnverified bool   `json:"-"`
}

type BlockedEntry struct {
	Path   string `json:"path"`
	Reason string `json:"reason"`
}

type Listing struct {
	ArchivePath     string         `json:"archivePath"`
	Format          string         `json:"format"`
	SourceSize      int64          `json:"sourceSize"`
	SourceModified  int64          `json:"sourceModified"`
	Entries         []Entry        `json:"entries"`
	ListedBytes     int64          `json:"listedBytes"`
	BlockedCount    int            `json:"blockedCount"`
	Blocked         []BlockedEntry `json:"blocked,omitempty"`
	Truncated       bool           `json:"truncated"`
	LimitReason     string         `json:"limitReason,omitempty"`
	MaxEntries      int            `json:"maxEntries"`
	MaxFileBytes    int64          `json:"maxFileBytes"`
	MaxExtractBytes int64          `json:"maxExtractBytes"`
	PathUnverified  bool           `json:"-"`
}

type ExtractOptions struct {
	ArchivePath    string
	Destination    string
	Selected       []string
	SourceSize     int64
	SourceModified int64
	FileMode       fs.FileMode
	DirMode        fs.FileMode
	Limits         Limits
	Checker        Checker
}

type ExtractProgress struct {
	TotalItems     int
	ProcessedItems int
	TotalBytes     int64
	ProcessedBytes int64
}

type SkippedEntry struct {
	Path           string `json:"path"`
	Reason         string `json:"reason"`
	PathUnverified bool   `json:"-"`
}

type ExtractReport struct {
	ArchivePath     string         `json:"archivePath"`
	Destination     string         `json:"destination"`
	Selected        []string       `json:"selected"`
	ExtractedFiles  int            `json:"extractedFiles"`
	ExtractedDirs   int            `json:"extractedDirs"`
	ExtractedBytes  int64          `json:"extractedBytes"`
	SkippedCount    int            `json:"skippedCount"`
	Skipped         []SkippedEntry `json:"skipped,omitempty"`
	CompletedAt     int64          `json:"completedAt"`
	PathsUnverified bool           `json:"-"`
}

type openedArchive struct {
	file      afero.File
	reader    io.Reader
	extractor archives.Extractor
	format    string
	info      os.FileInfo
}

func (opened *openedArchive) Close() error {
	if opened == nil || opened.file == nil {
		return nil
	}
	return opened.file.Close()
}

func List(ctx context.Context, filesystem afero.Fs, archivePath string, limits Limits) (*Listing, error) {
	if filesystem == nil {
		return nil, fmt.Errorf("文件系统不能为空")
	}
	limits = limits.normalized()
	archivePath, err := NormalizeFilesystemPath(archivePath)
	if err != nil {
		return nil, err
	}
	opened, err := openArchive(ctx, filesystem, archivePath)
	if err != nil {
		return nil, err
	}
	defer func() { _ = opened.Close() }()

	listing := &Listing{
		ArchivePath: archivePath, Format: opened.format,
		SourceSize: opened.info.Size(), SourceModified: opened.info.ModTime().UnixMilli(),
		Entries: make([]Entry, 0), MaxEntries: limits.MaxEntries,
		MaxFileBytes: limits.MaxFileBytes, MaxExtractBytes: limits.MaxExtractBytes,
	}
	knownEntries := make(map[string]Entry)
	requiredDirectories := make(map[string]struct{})
	err = opened.extractor.Extract(ctx, opened.reader, func(ctx context.Context, info archives.FileInfo) error {
		if err := ctx.Err(); err != nil {
			return err
		}
		entryPath, entryErr := cleanEntryPath(info.NameInArchive)
		if entryErr != nil {
			listing.addBlocked(info.NameInArchive, "路径不安全，已阻止")
			return nil
		}
		if entryPath == "." && info.IsDir() {
			return nil
		}
		if isLink(info) || (!info.IsDir() && !info.Mode().IsRegular()) {
			listing.addBlocked(entryPath, "链接或特殊文件不会被解压")
			return nil
		}
		if _, duplicate := knownEntries[entryPath]; duplicate {
			listing.addBlocked(entryPath, "归档中存在重复路径")
			return nil
		}
		for ancestor := path.Dir(entryPath); ancestor != "." && ancestor != "/"; ancestor = path.Dir(ancestor) {
			if existing, found := knownEntries[ancestor]; found && !existing.IsDir {
				listing.addBlocked(entryPath, "父路径是文件，目录结构冲突")
				return nil
			}
		}
		if !info.IsDir() {
			if _, neededAsDirectory := requiredDirectories[entryPath]; neededAsDirectory {
				listing.addBlocked(entryPath, "同一路径同时被声明为文件和目录")
				return nil
			}
		}
		if info.Size() < 0 || info.Size() > limits.MaxFileBytes {
			listing.Truncated = true
			listing.LimitReason = fmt.Sprintf("条目 %s 超过单文件安全上限", entryPath)
			return fs.SkipAll
		}
		if len(listing.Entries) >= limits.MaxEntries {
			listing.Truncated = true
			listing.LimitReason = fmt.Sprintf("归档条目超过 %d 项", limits.MaxEntries)
			return fs.SkipAll
		}
		if info.Size() > math.MaxInt64-listing.ListedBytes || listing.ListedBytes+info.Size() > limits.MaxExtractBytes {
			listing.Truncated = true
			listing.LimitReason = "归档声明内容超过总解压安全上限"
			return fs.SkipAll
		}
		entry := Entry{
			Path: entryPath, Name: path.Base(entryPath), IsDir: info.IsDir(),
			Size: info.Size(), Modified: info.ModTime().UnixMilli(),
		}
		listing.Entries = append(listing.Entries, entry)
		knownEntries[entryPath] = entry
		for ancestor := path.Dir(entryPath); ancestor != "." && ancestor != "/"; ancestor = path.Dir(ancestor) {
			requiredDirectories[ancestor] = struct{}{}
		}
		listing.ListedBytes += info.Size()
		return nil
	})
	if errors.Is(err, fs.SkipAll) {
		err = nil
	}
	if err != nil {
		return nil, fmt.Errorf("%w: %v", ErrInvalidArchive, err)
	}
	sort.SliceStable(listing.Entries, func(i, j int) bool {
		return listing.Entries[i].Path < listing.Entries[j].Path
	})
	return listing, nil
}

func Extract(
	ctx context.Context,
	filesystem afero.Fs,
	options ExtractOptions,
	reportProgress func(ExtractProgress) error,
) (*ExtractReport, error) {
	if filesystem == nil {
		return nil, fmt.Errorf("文件系统不能为空")
	}
	options.Limits = options.Limits.normalized()
	selected, err := NormalizeSelections(options.Selected, options.Limits.MaxSelected)
	if err != nil {
		return nil, err
	}
	destination, err := NormalizeFilesystemPath(options.Destination)
	if err != nil {
		return nil, err
	}
	options.ArchivePath, err = NormalizeFilesystemPath(options.ArchivePath)
	if err != nil {
		return nil, err
	}
	if options.Checker != nil && !options.Checker.Check(destination) {
		return nil, fmt.Errorf("没有访问解压目标的权限")
	}
	if err := requireSafeDirectory(filesystem, destination); err != nil {
		return nil, fmt.Errorf("解压目标不可用: %w", err)
	}

	listing, err := List(ctx, filesystem, options.ArchivePath, options.Limits)
	if err != nil {
		return nil, err
	}
	if listing.Truncated {
		return nil, fmt.Errorf("%w: %s", ErrLimitExceeded, listing.LimitReason)
	}
	if options.SourceSize != 0 && (listing.SourceSize != options.SourceSize || listing.SourceModified != options.SourceModified) {
		return nil, ErrArchiveChanged
	}

	expected := make(map[string]Entry)
	progress := ExtractProgress{}
	for _, entry := range listing.Entries {
		if !selectionMatches(selected, entry.Path) {
			continue
		}
		expected[entry.Path] = entry
		progress.TotalItems++
		if !entry.IsDir {
			progress.TotalBytes += entry.Size
		}
	}
	if len(expected) == 0 {
		return nil, fmt.Errorf("所选条目在压缩包中不存在")
	}
	if reportProgress != nil {
		if err := reportProgress(progress); err != nil {
			return nil, err
		}
	}

	opened, err := openArchive(ctx, filesystem, options.ArchivePath)
	if err != nil {
		return nil, err
	}
	defer func() { _ = opened.Close() }()
	if opened.info.Size() != listing.SourceSize || opened.info.ModTime().UnixMilli() != listing.SourceModified {
		return nil, ErrArchiveChanged
	}

	if options.FileMode == 0 {
		options.FileMode = 0o640
	}
	if options.DirMode == 0 {
		options.DirMode = 0o750
	}
	result := &ExtractReport{
		ArchivePath: options.ArchivePath, Destination: destination,
		Selected: append([]string(nil), selected...),
	}
	seen := make(map[string]struct{}, len(expected))
	err = opened.extractor.Extract(ctx, opened.reader, func(ctx context.Context, info archives.FileInfo) error {
		if err := ctx.Err(); err != nil {
			return err
		}
		entryPath, cleanErr := cleanEntryPath(info.NameInArchive)
		if cleanErr != nil || isLink(info) || (!info.IsDir() && !info.Mode().IsRegular()) {
			return nil
		}
		if !selectionMatches(selected, entryPath) {
			return nil
		}
		expectedEntry, exists := expected[entryPath]
		if !exists {
			return ErrArchiveChanged
		}
		if _, duplicate := seen[entryPath]; duplicate {
			return fmt.Errorf("%w: 重复条目 %s", ErrUnsafeEntry, entryPath)
		}
		seen[entryPath] = struct{}{}
		if expectedEntry.IsDir != info.IsDir() || expectedEntry.Size != info.Size() {
			return ErrArchiveChanged
		}

		target := joinWithin(destination, entryPath)
		if target == "" || (options.Checker != nil && !options.Checker.Check(target)) {
			return fmt.Errorf("%w: %s", ErrUnsafeEntry, entryPath)
		}
		if info.IsDir() {
			if err := createDirectory(filesystem, target, options.DirMode); err != nil {
				return err
			}
			result.ExtractedDirs++
		} else {
			written, skipped, err := extractFile(filesystem, info, target, options.FileMode, options.DirMode, options.Limits)
			if err != nil {
				return err
			}
			if skipped != "" {
				result.addSkipped(entryPath, skipped)
			} else {
				result.ExtractedFiles++
				result.ExtractedBytes += written
				progress.ProcessedBytes += written
			}
		}
		progress.ProcessedItems++
		if reportProgress != nil {
			return reportProgress(progress)
		}
		return nil
	})
	if err != nil {
		return nil, fmt.Errorf("解压失败: %w", err)
	}
	if len(seen) != len(expected) {
		return nil, ErrArchiveChanged
	}
	result.CompletedAt = time.Now().UnixMilli()
	return result, nil
}

func openArchive(ctx context.Context, filesystem afero.Fs, archivePath string) (*openedArchive, error) {
	archivePath, err := NormalizeFilesystemPath(archivePath)
	if err != nil {
		return nil, err
	}
	info, err := filesystem.Stat(archivePath)
	if err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() {
		return nil, fmt.Errorf("压缩包路径不是普通文件")
	}
	file, err := filesystem.Open(archivePath)
	if err != nil {
		return nil, err
	}
	format, reader, err := archives.Identify(ctx, path.Base(archivePath), file)
	if err != nil {
		_ = file.Close()
		if errors.Is(err, archives.NoMatch) {
			return nil, ErrUnsupportedFormat
		}
		return nil, fmt.Errorf("%w: %v", ErrInvalidArchive, err)
	}
	extractor, ok := format.(archives.Extractor)
	if !ok {
		_ = file.Close()
		return nil, ErrUnsupportedFormat
	}
	formatName, ok := supportedFormatName(format)
	if !ok {
		_ = file.Close()
		return nil, ErrUnsupportedFormat
	}
	return &openedArchive{file: file, reader: reader, extractor: extractor, format: formatName, info: info}, nil
}

func supportedFormatName(format archives.Format) (string, bool) {
	name := strings.TrimPrefix(strings.ToLower(format.Extension()), ".")
	switch name {
	case "zip", "7z", "rar", "tar", "tar.gz", "tar.bz2", "tar.xz", "tar.zst":
		return name, true
	default:
		return name, false
	}
}

func NormalizeFilesystemPath(value string) (string, error) {
	if value == "" || strings.ContainsRune(value, '\x00') || runtime.GOOS == "windows" &&
		(!utf8.ValidString(value) || strings.ContainsRune(value, '\\')) {
		return "", fmt.Errorf("%w: 文件系统路径无效", ErrUnsafeEntry)
	}
	return pathmeta.Clean(value), nil
}

func cleanEntryPath(value string) (string, error) {
	if value == "" || strings.ContainsRune(value, '\x00') {
		return "", ErrUnsafeEntry
	}
	if runtime.GOOS == "windows" && (!utf8.ValidString(value) || strings.ContainsRune(value, '\\')) {
		return "", ErrUnsafeEntry
	}
	// Inspect Windows-style traversal without converting a legal Linux filename.
	portable := strings.ReplaceAll(value, "\\", "/")
	if strings.HasPrefix(portable, "/") {
		return "", ErrUnsafeEntry
	}
	for _, segment := range strings.Split(portable, "/") {
		if segment == ".." {
			return "", ErrUnsafeEntry
		}
	}
	cleaned := path.Clean(value)
	if cleaned == ".." || strings.HasPrefix(cleaned, "../") {
		return "", ErrUnsafeEntry
	}
	first := strings.SplitN(portable, "/", 2)[0]
	if strings.Contains(first, ":") {
		return "", ErrUnsafeEntry
	}
	return cleaned, nil
}

func NormalizeSelections(values []string, maximum int) ([]string, error) {
	if maximum <= 0 {
		maximum = DefaultMaxSelected
	}
	if len(values) == 0 || len(values) > maximum {
		return nil, fmt.Errorf("解压选择必须包含 1–%d 个条目", maximum)
	}
	unique := make(map[string]struct{}, len(values))
	selected := make([]string, 0, len(values))
	for _, value := range values {
		cleaned, err := cleanEntryPath(value)
		if err != nil {
			return nil, fmt.Errorf("所选条目路径不安全: %q", value)
		}
		if _, exists := unique[cleaned]; exists {
			continue
		}
		unique[cleaned] = struct{}{}
		selected = append(selected, cleaned)
	}
	sort.Strings(selected)
	return selected, nil
}

func selectionMatches(selected []string, entry string) bool {
	for _, root := range selected {
		if root == "." || entry == root || strings.HasPrefix(entry, root+"/") {
			return true
		}
	}
	return false
}

func isLink(info archives.FileInfo) bool {
	return info.LinkTarget != "" || info.Mode()&os.ModeSymlink != 0
}

func joinWithin(destination, entry string) string {
	target := path.Join(destination, entry)
	if destination == "/" {
		return target
	}
	if target != destination && !strings.HasPrefix(target, destination+"/") {
		return ""
	}
	return target
}

func requireSafeDirectory(filesystem afero.Fs, directory string) error {
	if err := rejectSymlinkComponents(filesystem, directory); err != nil {
		return err
	}
	info, err := filesystem.Stat(directory)
	if err != nil {
		return err
	}
	if !info.IsDir() {
		return fmt.Errorf("目标不是目录")
	}
	return nil
}

func rejectSymlinkComponents(filesystem afero.Fs, target string) error {
	lstater, ok := filesystem.(afero.Lstater)
	if !ok {
		return nil
	}
	current := "/"
	for _, segment := range strings.Split(strings.TrimPrefix(target, "/"), "/") {
		if segment == "" {
			continue
		}
		current = path.Join(current, segment)
		info, _, err := lstater.LstatIfPossible(current)
		if errors.Is(err, os.ErrNotExist) {
			continue
		}
		if err != nil {
			return err
		}
		if info.Mode()&os.ModeSymlink != 0 {
			return fmt.Errorf("路径包含符号链接: %s", current)
		}
	}
	return nil
}

func createDirectory(filesystem afero.Fs, target string, mode fs.FileMode) error {
	if err := rejectSymlinkComponents(filesystem, target); err != nil {
		return err
	}
	info, err := filesystem.Stat(target)
	if err == nil {
		if info.IsDir() {
			return nil
		}
		return fmt.Errorf("目标已存在且不是目录: %s", target)
	}
	if !errors.Is(err, os.ErrNotExist) {
		return err
	}
	return filesystem.MkdirAll(target, mode)
}

func extractFile(
	filesystem afero.Fs,
	info archives.FileInfo,
	target string,
	fileMode fs.FileMode,
	dirMode fs.FileMode,
	limits Limits,
) (int64, string, error) {
	if info.Size() < 0 || info.Size() > limits.MaxFileBytes {
		return 0, "", fmt.Errorf("%w: %s", ErrLimitExceeded, info.NameInArchive)
	}
	if err := createDirectory(filesystem, path.Dir(target), dirMode); err != nil {
		return 0, "", err
	}
	if err := rejectSymlinkComponents(filesystem, target); err != nil {
		return 0, "", err
	}
	exists, err := afero.Exists(filesystem, target)
	if err != nil {
		return 0, "", err
	}
	if exists {
		return 0, "目标已存在，未覆盖", nil
	}

	source, err := info.Open()
	if err != nil {
		return 0, "", err
	}
	defer func() { _ = source.Close() }()
	temporary, err := afero.TempFile(filesystem, path.Dir(target), temporaryExtractPrefix)
	if err != nil {
		return 0, "", err
	}
	temporaryName := temporary.Name()
	keepTemporary := true
	defer func() {
		if keepTemporary {
			_ = filesystem.Remove(temporaryName)
		}
	}()

	written, copyErr := io.Copy(temporary, io.LimitReader(source, info.Size()+1))
	closeErr := temporary.Close()
	if copyErr != nil {
		return 0, "", copyErr
	}
	if closeErr != nil {
		return 0, "", closeErr
	}
	if written != info.Size() {
		return 0, "", fmt.Errorf("条目大小与归档声明不一致: %s", info.NameInArchive)
	}
	if err := filesystem.Chmod(temporaryName, fileMode); err != nil {
		return 0, "", err
	}
	if exists, err = afero.Exists(filesystem, target); err != nil {
		return 0, "", err
	} else if exists {
		return 0, "目标已存在，未覆盖", nil
	}
	if err := filesystem.Rename(temporaryName, target); err != nil {
		return 0, "", err
	}
	keepTemporary = false
	return written, "", nil
}

func (listing *Listing) addBlocked(entryPath, reason string) {
	listing.BlockedCount++
	if len(listing.Blocked) < maxBlockedDetails {
		listing.Blocked = append(listing.Blocked, BlockedEntry{Path: entryPath, Reason: reason})
	}
}

func (report *ExtractReport) addSkipped(entryPath, reason string) {
	report.SkippedCount++
	if len(report.Skipped) < maxSkippedDetails {
		report.Skipped = append(report.Skipped, SkippedEntry{Path: entryPath, Reason: reason})
	}
}
