STORAGE ANALYZER - modules (each its own page / class)
  Home            MainActivity     storage bar + one card per module
  Apps            AppsActivity     size / data / cache per app (Usage access)
  App detail      AppDetailActivity  tap an app: App size / User data (tap: what is inside, files it saved) / Cache
  Cache cleaner   CacheActivity    apps ranked by clearable cache, "Clear biggest first", freed-so-far counter (Usage access)
  Photos, Videos, Audio, Documents, Others   CategoryActivity   files 1 MB+ with select + delete (All files access)
  WhatsApp media  WhatsAppActivity per-folder sizes incl. sent copies; tap a folder to review + delete
  Leftover folders LeftoverActivity folders of uninstalled apps (Android/media + top-level); long-press to inspect, select + delete
  Hidden & trash  HiddenActivity    compares Android's Photos/Videos figure with visible files; finds Gallery trash, .pending files, thumbnail caches, .nomedia folders
  Shared scan     FileIndex.kt
BUILD: open this folder in Android Studio, let Gradle sync, connect an Android 11+ phone with USB debugging, press Run.
