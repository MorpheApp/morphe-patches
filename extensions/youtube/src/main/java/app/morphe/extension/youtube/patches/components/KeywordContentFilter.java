/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original first edition code:
 * https://github.com/ReVanced/revanced-integrations/pull/584
 * https://github.com/ReVanced/revanced-integrations/commit/0cbad9820577c476f1f29b6ac77611b38afbb950
 * https://github.com/ReVanced/revanced-integrations/commit/1ee99aa6f0b4af15eeca25c7e21e8a0f5e9d189a
 * https://github.com/ReVanced/revanced-integrations/commit/c3bfa77d62b15dedfed8f697583f2f0805f0c2c1
 * https://github.com/ReVanced/revanced-integrations/commit/75fa5797f70123f68d4676201503cf35dcef46dc
 * https://github.com/ReVanced/revanced-integrations/commit/3a3ceec4b596354dcccbf3516ef1634bd8819b90
 * https://github.com/ReVanced/revanced-integrations/commit/cda1f3160c12d239df1183799ead39526cbac20f
 * https://github.com/ReVanced/revanced-integrations/commit/d8d2a852d3879060bd95cc43d66c7cf195e82b43
 * https://github.com/ReVanced/revanced-integrations/commit/2f2eeea5a722b6b7053eb2825d16fa37938b4e9e
 * https://github.com/ReVanced/revanced-integrations/commit/5314dd90d16dc8565331c4cddce114956d85a173
 * https://github.com/MorpheApp/morphe-patches/commit/f5371ca998c019609c2b5558b3408ab1fec065c8
 * https://github.com/MorpheApp/morphe-patches/commit/017eac71a3f9542b8ad6221e3600797d6b97fae4
 * https://github.com/MorpheApp/morphe-patches/pull/1972
 * https://github.com/MorpheApp/morphe-patches/pull/4530
 * https://github.com/MorpheApp/morphe-patches/pull/5264
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.components;

import static app.morphe.extension.shared.StringRef.str;

import androidx.annotation.Nullable;

import com.google.protobuf.InvalidProtocolBufferException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.StringTrieSearch;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.components.BufferHideStatsTracker;
import app.morphe.extension.shared.patches.components.BufferPhraseFilter;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.patches.components.StringFilterGroup;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.LongSetting;
import app.morphe.extension.youtube.innertube.ElementTexts.CommentData;
import app.morphe.extension.youtube.innertube.ElementTexts.Element;
import app.morphe.extension.youtube.innertube.ElementTexts.ElementModel;
import app.morphe.extension.youtube.innertube.ElementTexts.InnertubeCommand;
import app.morphe.extension.youtube.innertube.ElementTexts.InnertubeCommandHolder;
import app.morphe.extension.youtube.innertube.ElementTexts.ListItemViewModel;
import app.morphe.extension.youtube.innertube.ElementTexts.LockupImage;
import app.morphe.extension.youtube.innertube.ElementTexts.LockupMetadataViewModel;
import app.morphe.extension.youtube.innertube.ElementTexts.MetadataPart;
import app.morphe.extension.youtube.innertube.ElementTexts.MetadataRow;
import app.morphe.extension.youtube.innertube.ElementTexts.PanelContent;
import app.morphe.extension.youtube.innertube.ElementTexts.PollOptionHolder;
import app.morphe.extension.youtube.innertube.ElementTexts.PollPostData;
import app.morphe.extension.youtube.innertube.ElementTexts.Post;
import app.morphe.extension.youtube.innertube.ElementTexts.PostAuthor;
import app.morphe.extension.youtube.innertube.ElementTexts.SharedPostViewModel;
import app.morphe.extension.youtube.innertube.ElementTexts.ShelfPost;
import app.morphe.extension.youtube.innertube.ElementTexts.ShortsLockupMetadata;
import app.morphe.extension.youtube.innertube.ElementTexts.ShortsLockupViewModel;
import app.morphe.extension.youtube.innertube.ElementTexts.VideoData;
import app.morphe.extension.youtube.innertube.ElementTexts.VideoPostData;
import app.morphe.extension.youtube.innertube.ElementTexts.ViewModelHolder;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.patches.dearrow.TitleCleanup;
import app.morphe.extension.youtube.patches.utils.FeedVideoCardsPatch;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.NavigationBar;
import app.morphe.extension.youtube.shared.PlayerType;

/**
 * <pre>
 * Allows hiding home feed and search results based on video title keywords and/or channel names,
 * hiding comments based on the comment text and/or the name of the author,
 * and hiding community posts based on the post text and/or the name of the channel.
 *
 * The buffer of a video or a comment is its element, which includes the model of the element.
 * Only the texts of the model that are shown are searched: the title of the video, the line below
 * the title (channel name, views and date), the text of the comment and the name of the author,
 * the text of the post, the name of the channel of the post, the options of a poll
 * and the video of a post.
 * Other texts of the buffer are not searched, such as the menu items, the accessibility labels,
 * the search text of the search results and the texts of the comment buttons.
 *
 * Keywords match any casing, and fancy text such as math bold letters matches the plain letters.
 * Keywords that start with @ also match the channel handle of the videos.
 * Collaboration videos match the names and the handles of all their channels.
 *
 * Limitations:
 * - Shorts in the Home and Subscriptions feeds do not show the channel name,
 *   so they are not filtered by channel name.
 * - Videos of the compact layout of older app versions do not include the channel name,
 *   so they are only filtered by title.
 * - Some layout component residue will remain, such as the video chapter previews for some search results.
 *   These components do not include the video title or channel name, and they
 *   appear outside the filtered components so they are not caught.
 * - When using whole word syntax, some keywords may need additional pluralized variations.
 * - Posts are not hidden on channel pages, on the community page of a channel and on the page of a post.
 * </pre>
 */
@SuppressWarnings("unused")
public final class KeywordContentFilter extends BufferPhraseFilter {

    /**
     * Minimum keyword/phrase length to prevent excessively broad content filtering.
     * Only applies when not using whole word syntax.
     */
    private static final int MINIMUM_KEYWORD_LENGTH = 3;

    /**
     * Start of the keywords that also match the channel handle of a video, such as "@handle".
     */
    private static final String HANDLE_PREFIX = "@";

    /**
     * Path that ends the path of the root element of a feed item, such as {@code identifier|hash|CellType|}.
     */
    private static final String ROOT_ELEMENT_PATH_END = "|CellType|";

    /**
     * Start and length of a channel id, which is the browse id of a channel page.
     */
    private static final String CHANNEL_ID_PREFIX = "UC";
    private static final int CHANNEL_ID_LENGTH = 24;

    private final StringFilterGroup commentsFilter = new StringFilterGroup(
            Settings.HIDE_KEYWORD_CONTENT_COMMENTS,
            "comment_thread.eml"
    );

    private record SearchChannel(String name, String handle) {}

    private static final String[] SEARCH_CHANNEL_TEMPLATES = { "compact_channel.eml", "shorts_shelf.eml" };
    private static final Map<String, SearchChannel> searchChannels = Collections.synchronizedMap(
            Utils.createSizeRestrictedMap(50));

    // The element hook can run before Litho creates the filter that owns the broad keyword pause.
    @Nullable
    private static volatile KeywordContentFilter instance;

    /**
     * Root elements of the community posts. Only the root element holds the whole post.
     */
    private final StringFilterGroup postsFilter = new StringFilterGroup(
            Settings.HIDE_KEYWORD_CONTENT_POSTS,
            "images_post_responsive_root.e",
            "images_post_root_slim.e",
            "options_post_responsive_root.e",
            "options_post_root.e",
            // Post of a shelf of posts.
            "post_base_wrapper_slim.e",
            "shared_post_root.e",
            "text_post_root_slim.e",
            "videos_post_responsive_root.e",
            "videos_post_root.e"
    );

    /**
     * The last value of {@link Settings#HIDE_KEYWORD_CONTENT_PHRASES}
     * parsed and loaded into {@link #keywordSearch}.
     * Allows changing the keywords without restarting the app.
     */
    private static volatile String lastKeywordPhrasesParsed;

    /**
     * Keywords in lower case, searched in the texts in lower case.
     */
    @Nullable
    private static volatile StringTrieSearch keywordSearch;

    /**
     * Parameter of a search of the keywords.
     */
    private static final class KeywordMatch {
        /**
         * If the searched text is a channel handle, which only the keywords that start with
         * {@link #HANDLE_PREFIX} match.
         */
        boolean searchingHandle;
        /**
         * The keyword as entered in the settings, if a keyword matched.
         */
        @Nullable
        String keyword;
    }

    private record Keyword(String phrase, boolean wholeWord) {
    }

    private static boolean phraseUsesWholeWordSyntax(String phrase) {
        return phrase.startsWith("\"") && phrase.endsWith("\"");
    }

    private static String stripWholeWordSyntax(String phrase) {
        return phrase.substring(1, phrase.length() - 1);
    }

    /**
     * Keywords and texts are compared as plain letters in lower case, so keywords match any casing
     * and titles written in fancy text, such as math bold letters.
     */
    private static String foldCase(String text) {
        return TitleCleanup.replaceFancyText(text).toLowerCase(Locale.ROOT);
    }

    /**
     * @return If the match is not surrounded by other letters.
     *         A match surrounded by numbers/symbols/punctuation is a whole word.
     */
    private static boolean isWholeWord(CharSequence text, int startIndex, int length) {
        if (startIndex > 0 && Character.isLetter(Character.codePointBefore(text, startIndex))) {
            return false;
        }
        final int endIndex = startIndex + length;
        return endIndex >= text.length() || !Character.isLetter(Character.codePointAt(text, endIndex));
    }

    private static synchronized void parseKeywords() { // Must be synchronized since Litho is multithreaded.
        String rawKeywords = Settings.HIDE_KEYWORD_CONTENT_PHRASES.get();

        //noinspection StringEquality
        if (rawKeywords == lastKeywordPhrasesParsed) {
            Logger.printDebug(() -> "Using previously initialized search");
            return; // Another thread won the race, and search is already initialized.
        }

        // Keyword in lower case -> keyword. Linked map so log statements are in the order of the setting.
        Map<String, Keyword> keywords = new LinkedHashMap<>();
        for (String phrase : rawKeywords.split("\n")) {
            // Remove any trailing spaces the user may have accidentally included.
            phrase = phrase.stripTrailing();
            if (phrase.isBlank()) continue;

            final boolean wholeWordMatching;
            if (phraseUsesWholeWordSyntax(phrase)) {
                if (phrase.length() == 2) {
                    continue; // Empty "" phrase
                }
                phrase = stripWholeWordSyntax(phrase);
                wholeWordMatching = true;
            } else if (phrase.length() < MINIMUM_KEYWORD_LENGTH && !isLanguageWithNoSpaces(phrase)) {
                // Allow phrases of 1 and 2 characters if using a
                // language that does not use spaces between words.

                // Do not reset the setting. Keep the invalid keywords so the user can fix the mistake.
                Utils.showSnackbarLong(str("morphe_hide_keyword_toast_invalid_length", phrase, MINIMUM_KEYWORD_LENGTH));
                continue;
            } else {
                wholeWordMatching = false;
            }

            // If the phrase is declared both with and without quotes, use without quotes since it is broader.
            String key = foldCase(phrase);
            Keyword existing = keywords.get(key);
            if (existing == null) {
                keywords.put(key, new Keyword(phrase, wholeWordMatching));
            } else if (existing.wholeWord() && !wholeWordMatching) {
                keywords.put(key, new Keyword(phrase, false));
            }
        }

        StringTrieSearch search = new StringTrieSearch();
        for (Map.Entry<String, Keyword> entry : keywords.entrySet()) {
            Keyword keyword = entry.getValue();
            final boolean isWholeWord = keyword.wholeWord();
            final boolean matchesHandles = keyword.phrase().startsWith(HANDLE_PREFIX);

            search.addPattern(entry.getKey(), (textSearched, startIndex, matchLength, callbackParameter) -> {
                KeywordMatch match = (KeywordMatch) callbackParameter;
                if (match.searchingHandle && !matchesHandles) {
                    return false;
                }
                if (isWholeWord && !isWholeWord(textSearched, startIndex, matchLength)) {
                    return false;
                }

                Logger.printDebug(() -> (isWholeWord ? "Matched whole keyword: '"
                        : "Matched keyword: '") + keyword.phrase() + "'");
                match.keyword = keyword.phrase();
                return true;
            });
        }

        Logger.printDebug(() -> "Search using: (" + search.getEstimatedMemorySize() + " KB) keywords: " + keywords.keySet());

        keywordSearch = search;
        lastKeywordPhrasesParsed = rawKeywords; // Must set last.
    }

    public KeywordContentFilter() {
        super(); // The groups are registered below because instance fields initialize after super().
        addPathCallbacks(commentsFilter, postsFilter);
        instance = this;
        // Keywords are parsed on first call to isFiltered().
    }

    @Override
    protected void reparseIfNeeded() {
        // Field is intentionally compared using reference equality.
        //noinspection StringEquality
        if (Settings.HIDE_KEYWORD_CONTENT_PHRASES.get() != lastKeywordPhrasesParsed) {
            // User changed the keywords or whole word setting.
            parseKeywords();
        }
    }

    @Override
    protected boolean isActiveForFeedContext(ContextInterface contextInterface) {
        return isActiveForFeedContext(contextInterface, Settings.HIDE_KEYWORD_CONTENT_HOME,
                Settings.HIDE_KEYWORD_CONTENT_SEARCH, Settings.HIDE_KEYWORD_CONTENT_SUBSCRIPTIONS);
    }

    /**
     * @return If the setting of the feed that is shown is on: the home feed, the search results or
     *         the Subscriptions feed. The videos below the player are treated as the home feed.
     */
    static boolean isActiveForFeedContext(ContextInterface contextInterface, BooleanSetting home,
                                          BooleanSetting search, BooleanSetting subscriptions) {
        // Must check player type first, as search bar can be active behind the player.
        if (PlayerType.getCurrent().isMaximizedOrFullscreen()) {
            // For now, consider the under video results the same as the home feed.
            return home.get();
        }

        // Must check second, as search can be from any tab.
        if (NavigationBar.isSearchBarActive()) {
            return search.get();
        }

        // Avoid checking navigation button status if all other settings are off.
        final boolean hideHome = home.get();
        final boolean hideSubscriptions = subscriptions.get();
        if (!hideHome && !hideSubscriptions) {
            return false;
        }

        NavigationBar.NavigationButton selectedNavButton =
                NavigationBar.NavigationButton.getSelectedNavigationButton(contextInterface);
        if (selectedNavButton == null) {
            return hideHome; // Unknown tab, treat the same as home.
        }

        return switch (selectedNavButton) {
            case HOME -> hideHome;
            case SUBSCRIPTIONS -> hideSubscriptions;
            // User is in the Library or notifications.
            default -> false;
        };
    }

    @Override
    @Nullable
    protected String matchBuffer(byte[] buffer, CharSequence path, StringFilterGroup matchedGroup,
                                 ContextInterface contextInterface) {
        StringTrieSearch search = keywordSearch;
        if (search == null || search.numberOfPatterns() == 0) {
            return null;
        }

        if (matchedGroup == postsFilter && (!Utils.endsWith(path, ROOT_ELEMENT_PATH_END) || isPostsPage())) {
            return null;
        }

        try {
            ElementModel model = findModel(Element.parseFrom(buffer));
            if (model == null) {
                return null;
            }

            KeywordMatch match = new KeywordMatch();
            if (!matchesShownTexts(search, model, match)) {
                return null;
            }

            recordHide(matchedGroup, buffer, contextInterface);
            return match.keyword;
        } catch (InvalidProtocolBufferException ex) {
            Logger.printException(() -> "matchBuffer failure", ex);
            return null;
        }
    }

    /**
     * Elements of the components inside a video or a comment include the same model, so they
     * match the same keywords. Elements of the texts and the containers have no model.
     *
     * @return The model of the element or of the first element inside it, in the order of the
     *         elements, that has the model of a video, a comment or a post. Null if none.
     *         Models inside other models are not used, such as the Short of the
     *         Remix menu item of a video.
     */
    @Nullable
    static ElementModel findModel(Element element) {
        ElementModel model = element.getType().getComponentType().getModel();
        ViewModelHolder viewModel = model.getYoutubeModel().getViewModel();
        if (model.hasVideoWithContextModel()
                || viewModel.hasLockupViewModel()
                || viewModel.hasShortsLockupViewModel()
                || model.hasCommentModel()
                || model.hasCompactVideoMetadataModel()
                || getPost(model) != null
                || model.hasVideoPostModel()
                || model.hasVideoPostResponsiveModel()
                || model.hasPollPostModel()
                || model.hasPollPostResponsiveModel()
                || model.hasShelfPostModel()
                || viewModel.hasSharedPostViewModel()) {
            return model;
        }

        for (Element child : element.getChildrenList()) {
            ElementModel childModel = findModel(child);
            if (childModel != null) {
                return childModel;
            }
        }

        return null;
    }

    /**
     * Keywords that match the channel name would hide all posts of the posts page of a channel,
     * which then stays empty while YouTube loads all the posts of the channel.
     *
     * @return If a page that shows the posts of a channel or a post is shown, where posts are not hidden:
     *         a channel page, the community page of a channel or the page of a post.
     */
    private static boolean isPostsPage() {
        if (LayoutComponentsFilter.isCommunityPostPage()) {
            return true;
        }

        String browseId = LayoutComponentsFilter.browseId;
        return browseId.length() == CHANNEL_ID_LENGTH && browseId.startsWith(CHANNEL_ID_PREFIX)
                && !PlayerType.getCurrent().isMaximizedOrFullscreen();
    }

    /**
     * @return The post of a model of a post that has no attachment with texts. Null if none.
     */
    @Nullable
    private static Post getPost(ElementModel model) {
        if (model.hasTextPostModel()) {
            return model.getTextPostModel().getData().getPost();
        }
        if (model.hasImagesPostModel()) {
            return model.getImagesPostModel().getData().getPost();
        }
        if (model.hasImagesPostResponsiveModel()) {
            return model.getImagesPostResponsiveModel().getData().getPost();
        }
        return null;
    }

    /**
     * @return If a keyword matches a text of the model that is shown,
     *         or a channel of a video or a post.
     */
    private static boolean matchesShownTexts(StringTrieSearch search, ElementModel model, KeywordMatch match) {
        if (model.hasVideoWithContextModel()) {
            return matchesVideo(search, model.getVideoWithContextModel().getData().getVideoData(), match);
        }

        if (model.hasCommentModel()) {
            CommentData comment = model.getCommentModel().getData();
            return matches(search, comment.getProperties().getContent().getContent(), match)
                    || matches(search, comment.getAuthor().getDisplayName(), match);
        }

        if (model.hasCompactVideoMetadataModel()) {
            return matches(search, model.getCompactVideoMetadataModel().getMetadata().getTitle(), match);
        }

        Post post = getPost(model);
        if (post != null) {
            return matchesPost(search, post, match);
        }
        if (model.hasVideoPostModel()) {
            return matchesVideoPost(search, model.getVideoPostModel().getData(), match);
        }
        if (model.hasVideoPostResponsiveModel()) {
            return matchesVideoPost(search, model.getVideoPostResponsiveModel().getData(), match);
        }
        if (model.hasPollPostModel()) {
            return matchesPollPost(search, model.getPollPostModel().getData(), match);
        }
        if (model.hasPollPostResponsiveModel()) {
            return matchesPollPost(search, model.getPollPostResponsiveModel().getData(), match);
        }
        if (model.hasShelfPostModel()) {
            ShelfPost shelfPost = model.getShelfPostModel().getData().getPost();
            return matches(search, shelfPost.getBody().getText().getText().getContent(), match)
                    || matchesAuthor(search, shelfPost.getHeader().getAuthorRow().getAuthor(), match);
        }

        ViewModelHolder viewModel = model.getYoutubeModel().getViewModel();
        if (viewModel.hasLockupViewModel()) {
            return matchesLockup(search, viewModel.getLockupViewModel().getMetadata()
                    .getLockupMetadataViewModel(), match);
        }
        if (viewModel.hasSharedPostViewModel()) {
            return matchesSharedPost(search, viewModel.getSharedPostViewModel(), match);
        }

        ShortsLockupViewModel shorts = viewModel.getShortsLockupViewModel();
        ShortsLockupMetadata metadata = shorts.getMetadata();
        if (matches(search, shorts.getTitle().getText().getContent(), match)
                || matches(search, metadata.getChannelName().getContent(), match)) {
            return true;
        }

        return matchesChannel(search, metadata.getAvatar().getAvatarViewModel().getOnTap(), match);
    }

    /**
     * @return If a keyword matches the title or the line below the title of a video,
     *         or a channel of the video.
     */
    private static boolean matchesVideo(StringTrieSearch search, VideoData video, KeywordMatch match) {
        return matchesLockup(search, video.getLockupMetadata().getLockupMetadataViewModel(), match)
                || matchesChannel(search, video.getAvatar().getEndpoint(), match)
                || matchesImage(search, video.getDecoratedAvatar(), match)
                || matchesImage(search, video.getAvatarStack(), match);
    }

    /**
     * @return If a keyword matches the title or the lines below the title of a video,
     *         or a channel of the avatar of the card layout.
     */
    private static boolean matchesLockup(StringTrieSearch search, LockupMetadataViewModel metadata,
                                         KeywordMatch match) {
        if (matches(search, metadata.getTitle().getContent(), match)) {
            return true;
        }
        for (MetadataRow row : metadata.getMetadata().getContentMetadataViewModel().getMetadataRowsList()) {
            for (MetadataPart part : row.getMetadataPartsList()) {
                if (matches(search, part.getText().getContent(), match)) {
                    return true;
                }
            }
        }

        return matchesImage(search, metadata.getImage(), match);
    }

    private static boolean matchesImage(StringTrieSearch search, LockupImage image, KeywordMatch match) {
        return matchesChannel(search, image.getDecoratedAvatarViewModel()
                .getRendererContext().getCommandContext().getOnTap(), match)
                || matchesChannel(search, image.getAvatarStackViewModel()
                .getRendererContext().getCommandContext().getOnTap(), match);
    }

    private static boolean matchesPost(StringTrieSearch search, Post post, KeywordMatch match) {
        return matches(search, post.getText().getText().getContent(), match)
                || matchesAuthor(search, post.getHeader().getAuthor(), match);
    }

    private static boolean matchesVideoPost(StringTrieSearch search, VideoPostData data, KeywordMatch match) {
        return matchesPost(search, data.getPost(), match)
                || matchesVideo(search, data.getAttachment().getVideo(), match);
    }

    private static boolean matchesPollPost(StringTrieSearch search, PollPostData data, KeywordMatch match) {
        if (matchesPost(search, data.getPost(), match)) {
            return true;
        }
        for (PollOptionHolder option : data.getAttachment().getPoll().getOptionsList()) {
            if (matches(search, option.getOption().getText(), match)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return If a keyword matches the text or the channel of the post that shares a post,
     *         or the shared post.
     */
    private static boolean matchesSharedPost(StringTrieSearch search, SharedPostViewModel sharedPost,
                                             KeywordMatch match) {
        if (matches(search, sharedPost.getHeader().getText().getContent(), match)
                || matchesChannel(search, sharedPost.getAvatar().getAvatarViewModel().getOnTap(), match)) {
            return true;
        }

        ElementModel model = findModel(sharedPost.getOriginal().getElementHolder().getElement());
        return model != null && matchesShownTexts(search, model, match);
    }

    private static boolean matchesAuthor(StringTrieSearch search, PostAuthor author, KeywordMatch match) {
        return matches(search, author.getName(), match)
                || matchesChannel(search, author.getEndpoint(), match);
    }

    /**
     * @param endpoint Command that opens a channel, or the sheet with the channels of a collaboration video.
     * @return If a keyword that is a handle matches the handle of a channel,
     *         or a keyword matches the name of a channel of the sheet.
     */
    private static boolean matchesChannel(StringTrieSearch search, InnertubeCommandHolder endpoint,
                                          KeywordMatch match) {
        InnertubeCommand command = endpoint.getInnertubeCommand();
        if (matchesHandle(search, command.getBrowseEndpoint().getCanonicalBaseUrl(), match)) {
            return true;
        }

        for (PanelContent content : command.getShowSheetCommand().getPanelLoadingStrategy()
                .getInlineContent().getPanelContentRenderer().getContentsList()) {
            ListItemViewModel channel = content.getElementRenderer().getNewElement().getType()
                    .getComponentType().getModel().getYoutubeModel().getViewModel().getListItemViewModel();
            if (matches(search, channel.getTitle().getContent(), match)
                    || matchesHandle(search, channel.getSubtitle().getContent(), match)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesHandle(StringTrieSearch search, String text, KeywordMatch match) {
        match.searchingHandle = true;
        final boolean matched = matches(search, text, match);
        match.searchingHandle = false;
        return matched;
    }

    private static boolean matches(StringTrieSearch search, String text, KeywordMatch match) {
        return !text.isEmpty() && search.matches(foldCase(text), match);
    }

    /**
     * A creator's Shorts shelf links to the channel ID but omits the handle. For example, a shelf titled
     * "Latest from Jane's Kitchen" could belong to {@code @cookwithjane}. The preceding channel card
     * supplies the name and handle for that ID.
     */
    public static byte[] hideSearchShortsShelf(byte[] element) {
        try {
            if (!Settings.HIDE_KEYWORD_CONTENT_SEARCH.get() || Settings.HIDE_KEYWORD_CONTENT_PHRASES.get().isEmpty()
                    || PlayerType.getCurrent().isMaximizedOrFullscreen() || !NavigationBar.isSearchBarActive()) {
                return element;
            }

            String elementText = FeedVideoCardsPatch.getElementText(element);
            if (FeedVideoCardsPatch.lacksAll(elementText, SEARCH_CHANNEL_TEMPLATES)) return element;

            var model = FeedCardLayoutOuterClass.Element.parseFrom(element).getType().getComponentType().getModel();
            if (model.hasCompactChannelModel()) {
                var channel = model.getCompactChannelModel().getData();
                String channelId = channel.getEndpoint().getInnertubeCommand().getBrowseEndpoint().getBrowseId();
                if (!channelId.isEmpty()) {
                    searchChannels.put(channelId, new SearchChannel(channel.getName(), channel.getHandle()));
                }
                return element;
            }
            if (instance != null && System.currentTimeMillis() < instance.timeToResumeFiltering) return element;

            var header = model.getShortsShelfModel().getHeader();
            String channelId = header.getEndpoint().getInnertubeCommand().getBrowseEndpoint().getBrowseId();
            SearchChannel channel = searchChannels.get(channelId);
            if (channel == null) return element;

            parseKeywords();
            StringTrieSearch search = keywordSearch;
            KeywordMatch match = new KeywordMatch();
            if (matches(search, channel.name(), match)) {
                return new byte[0];
            }
            match.searchingHandle = true;
            if (matches(search, channel.handle(), match)) {
                return new byte[0];
            }
        } catch (Exception ex) {
            Logger.printException(() -> "hideSearchShortsShelf failure", ex);
        }
        return element;
    }

    @Override
    protected void onBroadFilterDetected(@Nullable String matched) {
        Utils.showSnackbarLong(str("morphe_hide_keyword_toast_invalid_broad", matched));
    }

    private void recordHide(StringFilterGroup matchedGroup, byte[] buffer, ContextInterface contextInterface) {
        // Posts have no video id, and the hide statistics have no source for posts.
        if (matchedGroup == postsFilter) return;

        Source source = detectSource(matchedGroup, contextInterface);
        String videoId = getVideoIdForSource(source, buffer);
        // If ID extraction fails the hide still happens; only stats are skipped
        // to avoid double-counting the same card as it re-enters the viewport.
        if (videoId == null) return;

        if (sharedTracker.recordHide(videoId, source, System.currentTimeMillis())) {
            LongSetting counter = allTimeCounterFor(source);
            if (counter != null) counter.save(counter.get() + 1);
        }
    }

    private Source detectSource(StringFilterGroup matchedGroup, ContextInterface contextInterface) {
        if (matchedGroup == commentsFilter) return Source.COMMENTS;
        // Player fullscreen: treat under-video results as home (mirrors isActiveForFeedContext).
        if (PlayerType.getCurrent().isMaximizedOrFullscreen()) return Source.HOME;
        if (NavigationBar.isSearchBarActive()) return Source.SEARCH;
        NavigationBar.NavigationButton nav = NavigationBar.NavigationButton.getSelectedNavigationButton(contextInterface);
        return nav == NavigationBar.NavigationButton.SUBSCRIPTIONS ? Source.SUBSCRIPTIONS : Source.HOME;
    }

    @Nullable
    private static String getVideoIdForSource(Source source, byte[] buffer) {
        if (source == Source.COMMENTS) {
            // Comment threads carry no thumbnail URL for the parent video; the currently
            // open player's video ID is authoritative.
            String id = VideoInformation.getVideoId();
            return id.isEmpty() ? null : id;
        }
        return extractVideoIdFromBuffer(buffer);
    }

    private static LongSetting allTimeCounterFor(Source source) {
        return switch (source) {
            case HOME -> Settings.KEYWORD_HIDE_COUNT_HOME;
            case SUBSCRIPTIONS -> Settings.KEYWORD_HIDE_COUNT_SUBSCRIPTIONS;
            case SEARCH -> Settings.KEYWORD_HIDE_COUNT_SEARCH;
            case COMMENTS -> Settings.KEYWORD_HIDE_COUNT_COMMENTS;
        };
    }

    /** Returns the total 24h hide count across all sources. */
    public static int hidesInLast24Hours() {
        return sharedTracker.totalSize(System.currentTimeMillis());
    }

    /** Returns the 24h hide count for a given source. */
    public static int hidesInLast24Hours(Source source) {
        return sharedTracker.sourceSize(source, System.currentTimeMillis());
    }

    /** Clears the 24h tracker. Called from the reset dialogs. */
    public static void resetHidesTracker() {
        sharedTracker.reset();
    }

    /** Shared static reference so the UI can query without holding a filter instance. */
    private static final BufferHideStatsTracker sharedTracker =
            new BufferHideStatsTracker(Settings.KEYWORD_HIDES_24H);
}
