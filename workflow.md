Fork maintenance and customization workflow for morphe-patches

Goal
- First fetch upstream and update my code i noticed now they pushed right now.
- Apply my preferred YouTube and YouTube Music defaults.
- Release from my fork without requiring MorpheApp private secrets.

Repository setup
1. Ensure taking pull from remotes:
   - origin: my fork
   - upstream: https://github.com/MorpheApp/morphe-patches.git
2. Sync main:
   - git fetch upstream
   - git checkout main
   - git merge --ff-only upstream/main
   - git push origin main

Global patch behavior
- Disable environment check warning in CheckEnvironmentPatch.java near:
  if (!Check.shouldRun() && !DEBUG_ALWAYS_SHOW_CHECK_FAILED_DIALOG) { ... }
- Use:
  // Environment checks disabled.
  Check.disableForever();
  return;

YouTube defaults to enforce
1. MORPHE Youtube package name
   - Set MORPHE_YOUTUBE_PACKAGE_NAME to com.google.android.apps.youtube.kids
   
2. External downloader
   - Disable Override download action button. morphe_external_downloader_action_button = false
   - Enable show external download button.(morphe_external_downloader = true)
   - Set default package name to com.video.fun.app.

3. Player
   - Enable hide endscreen cards button.(morphe_hide_end_screen_cards = true)
   - Enable hide end screen suggested video button.(morphe_hide_end_screen_suggested_video = true)

4. Video
   - Enable remember video quality changes.(morphe_remember_video_quality_last_selected = true)
   - Enable remember playback speed changes.(morphe_remember_playback_speed_last_selected = true)
   - Enable advanced video quality menu.(morphe_advanced_video_quality_menu = true)
   - Enable remember shorts quality changes.(morphe_remember_shorts_quality_last_selected = true)

5. General and navigation
   - Set default app name to custom preset index 2.
   - Set custom name entry 2 to Premium Youtube.
   - Set default header logo to Premium.(morphe_header_logo = HeaderLogo.PREMIUM)
   - Set default app icon style to Original.

6. Shorts
   - Enable hide shorts on home feed button by default.(morphe_hide_shorts_home = true)

7. Seekbar
   - Enable slide to seek.(morphe_slide_to_seek = true)
   - Enable tap to seek.(morphe_tap_to_seek = true)

8. Feed
   - Disable hide mix playlists button by default.
   - Disable hide 'you may like' section button by default.(morphe_hide_you_may_like_section = false)
   - Disable hide 'notify me' button by default.(morphe_hide_notify_me_button = false)
   - Disable hide 'show more' button by default.(morphe_hide_show_more_button = false)
   - Disable hide latest post button by default.(morphe_hide_latest_posts = false)
   - Enable hide YouTube Doodles button by default.(morphe_hide_youtube_doodles = true)

9. Swipe controls
   - Enable swipe to change videos.(morphe_swipe_change_video = true)
   - Enable brightness gesture.(morphe_swipe_brightness = true)
   - Enable volume gesture.(morphe_swipe_volume = true)
   - Enable auto-brightness gesture.(morphe_swipe_lowest_value_enable_auto_brightness = true)

10. Miscellaneous
   - Disable announcements by default.(morphe_announcements = false)

11. About
   - Hide the About section inside Morphe/Premium YouTube settings. ("About" preference to the top.)

YouTube Music defaults to enforce
1. YT Music package name
   - Set MORPHE_MUSIC_PACKAGE_NAME to app.revanced.android.apps.youtube.music in Constants.kt and other relevant files.
   - Set targetPackage and morphe_music_package_name to app.revanced.android.apps.youtube.music in OverrideYouTubeMusicActionsPatch.kt and Settings.java respectively.

2. General
   - Set custom branding entry 2 to YT Music Premium.
   - Set default icon style to Original.

Expected files to modify
- BaseSetting.java
- Settings.java
- strings.xml
  - Update UI display text from Morphe to Premium Youtube (settings button labels, import/export text, Android VR note, morphe_settings_title to Extra Settings, morphe_settings_submenu_title to Premium Settings)
- CustomBrandingPatch.java
- BaseCustomBrandingPatch.kt
- Constants.kt
- CheckEnvironmentPatch.java

Release process from fork
1. Commit using semantic-release trigger format (example):
   build(Needs bump): set preferred defaults
2. Push to main on fork.
3. GitHub Actions release workflow should:
   - Build patches.
   - Publish release assets using GITHUB_TOKEN.
   - Skip website deploy and FCM steps when related secrets/vars are missing.
4. Wait and fix until a successful release workflow run completes.

Manager usage notes
1. Use this source URL in Morphe Manager:
   https://raw.githubusercontent.com/atanuroy22/morphe-patches/refs/heads/main/patches-bundle.json
2. Ensure the JSON includes:
   - version
   - download_url
   - signature_download_url (or empty if not used by your flow)
   - created_at
   - description
3. Keep that JSON updated for each release.

Important
- Do not run destructive git commands.
- Keep changes scoped to defaults only.
- Update CHANGELOG.md whenever behavior changes.
- Never change a single word inside the workflow.md file(Follow all the steps but Never edit this file strictly). 

**NOW COMPLETE THE RELEASE PROCESS**
