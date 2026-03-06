# Raccoon File Manager — Architecture Overview

## Pattern

Single-Activity MVVM with Fragment-based navigation (Jetpack Navigation + Safe Args).
`MainActivity` hosts all Fragments; `MainViewModel` (shared via `activityViewModels()`)
holds scan results, file lists, and operation state.

## Package Structure

```
com.filecleaner.app/
├── data/                    # Data models: FileItem, DirectoryNode, UserPreferences
│   └── cloud/               # CloudProvider interface + 4 implementations + OAuthHelper
├── services/                # ScanService (foreground service for antivirus)
├── ui/                      # Feature-per-package Fragments
│   ├── adapters/            # Shared RecyclerView adapters (FileAdapter, BrowseAdapter)
│   ├── common/              # BaseFileListFragment, ConvertDialog, DirectoryPickerDialog
│   ├── widget/              # RaccoonBubble custom view
│   └── [feature]/           # browse, cloud, dashboard, duplicates, junk, large,
│                            # optimize, security, settings, viewer, arborescence, etc.
├── utils/                   # Business logic: FileScanner, DuplicateFinder, JunkFinder,
│   │                        # StorageOptimizer, ScanCache, RetryHelper, SearchQueryParser
│   └── antivirus/           # 5 scanner modules (integrity, signature, privacy, network, verification)
└── viewmodel/               # MainViewModel + extracted ClipboardManager, NavigationEvents
```

## Key Data Flow

1. **Scan pipeline:**
   `MainViewModel.startScan()` → `FileScanner.scanWithTree()` (filesystem walk)
   → `DuplicateFinder.findDuplicates()` (3-stage: size group → partial hash → full hash)
   → `JunkFinder.findJunk()` / `findLargeFiles()` → LiveData updates → UI observes

2. **Cache persistence:**
   `ScanCache.save()` writes JSON to disk (debounced, 3s). On cold start,
   `ScanCache.load()` restores file lists without rescanning.

3. **Delete with undo:**
   `deleteFiles()` moves to `.trash/` dir → UI shows Snackbar with Undo →
   `undoDelete()` restores from trash (with F-025 duplicate snapshot for O(1) restore) →
   `confirmDelete()` permanently deletes when undo window expires.

4. **Cloud browsing:**
   `CloudProvider` interface (list, download, upload, delete, mkdir, auth) →
   4 implementations: Google Drive (OAuth + REST), SFTP (JSch), WebDAV, GitHub.
   OAuth uses PKCE flow with custom URI scheme deep links.

5. **Antivirus:**
   `ScanService` (foreground) runs 5 scanner modules → results stored in
   `AtomicReference<ScanStatus>` + `MutableLiveData` → `AntivirusFragment` observes.

## Design Decisions

- **Single ViewModel:** All scan/file state lives in `MainViewModel` so any Fragment
  can observe it. `ClipboardManager` and `NavigationEvents` are extracted managers.
- **Trash-based undo:** Files are moved (not deleted) to enable instant undo.
  `ConcurrentHashMap` + `Mutex` guards concurrent access.
- **Binary size units:** 1 KB = 1024 bytes throughout (consistent with Android).
- **DiffUtil with payloads:** Adapters use partial rebind for efficient list updates.
- **Reduced motion:** `MotionUtil.isReducedMotion()` disables animations app-wide.
