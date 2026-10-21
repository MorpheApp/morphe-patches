/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3897
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.ask;

import android.util.Base64;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Builds the command that shows the Ask panel of any video.
 * <p>
 * The server sends the Ask panel only with some videos, in the response of the watch page.
 * The video id is in the continuation tokens of the panel, which nest protobuf messages
 * in base64 several times. Replacing the video id keeps the length of every token,
 * so the panel of another video is built from the panel of any video, without rewriting
 * the length prefixes of the protobuf messages.
 */
final class AskPanelCommand {

    private static final String ASK_PANEL_ID = "PAyouchat";

    /**
     * Shows the panel above the page, such as the feed, instead of on the watch page.
     */
    private static final int SURFACE_BROWSE = 2;

    // Command fields.
    private static final int SHOW_ENGAGEMENT_PANEL_ENDPOINT_FIELD = 138681778;

    // ShowEngagementPanelEndpoint fields.
    private static final int ENGAGEMENT_PANEL_FIELD = 2;
    private static final int IDENTIFIER_FIELD = 10;

    // EngagementPanel fields, the panel is the one supported renderer.
    private static final int ENGAGEMENT_PANEL_RENDERER_FIELD = 138681066;

    // EngagementPanelSectionListRenderer fields.
    private static final int PANEL_PANEL_IDENTIFIER_FIELD = 1;
    private static final int PANEL_IDENTIFIER_FIELD = 18;
    /**
     * Registers the panel again when a panel with its id is already registered,
     * so the panel of the previous video is replaced.
     */
    private static final int PANEL_REPLACE_REGISTERED_FIELD = 20;

    // PanelIdentifier fields.
    private static final int IDENTIFIER_SURFACE_FIELD = 1;
    private static final int IDENTIFIER_TAG_FIELD = 3;

    // Watch page fields.
    private static final int WATCH_PAGE_ENGAGEMENT_PANELS_FIELD = 25;
    /**
     * Updates of app state that a response of any type carries, such as the Elements resources it attaches.
     */
    private static final int RESPONSE_FRAMEWORK_UPDATES_FIELD = 777;

    // Suggestion fields, of the suggestions that the panel shows before the welcome.
    /**
     * Identifies the suggestion in the command that its tap runs.
     */
    private static final int SUGGESTION_SELECTION_ID_FIELD = 11;
    private static final int SUGGESTION_TOKEN_FIELD = 13;
    private static final int SUGGESTION_ICON_FIELD = 31;
    private static final int SUGGESTION_ICON_SUMMARIZE = 268;
    /**
     * The key of the entity that holds the suggestion chosen in the panel.
     */
    private static final String SELECTION_ENTITY_KEY = "selected_index_entity";

    // Token fields. A token is base64 of a message that holds base64 of another message.
    private static final int TOKEN_FIELD = 377091426;
    private static final int TOKEN_PANEL_ID_FIELD = 2;
    private static final int TOKEN_REQUEST_FIELD = 3;
    private static final int REQUEST_FIELD = 162;
    private static final int REQUEST_VIDEO_ID_FIELD = 2;
    private static final int REQUEST_TYPE_FIELD = 5;
    /**
     * Set in the request type of the welcome request.
     */
    private static final int REQUEST_TYPE_WELCOME_FIELD = 4;

    /**
     * The command that a suggestion tap runs selects the suggestion with this message, which holds
     * the selection id of the suggestion in field 1.
     */
    private static final int SELECTION_FIELD = 439374522;

    // Form data fields, sent with each request of the chat.
    private static final int FORM_DATA_INPUT_COMPOSER_FIELD = 1007;
    private static final int INPUT_COMPOSER_PLAYER_OFFSET_FIELD = 3;

    /**
     * The suggestions are nested a dozen messages deep.
     */
    private static final int MAX_MESSAGE_DEPTH = 40;

    /**
     * A token is longer, and shorter base64 is not decoded to look for one.
     */
    private static final int MIN_TOKEN_LENGTH = 64;

    static final int WIRE_TYPE_VARINT = 0;
    private static final int WIRE_TYPE_FIXED64 = 1;
    static final int WIRE_TYPE_LENGTH_DELIMITED = 2;
    private static final int WIRE_TYPE_FIXED32 = 5;

    private static final int VIDEO_ID_LENGTH = 11;

    /**
     * The tokens nest base64 at most a few times.
     */
    private static final int MAX_TOKEN_DEPTH = 4;

    /**
     * A base64 run, with its padding that some tokens percent-encode.
     * Shorter runs are not tokens, and a video id is shorter.
     */
    private static final Pattern BASE64_RUN = Pattern.compile("[A-Za-z0-9\\-_+/]{16,}(?:%3D|=)*");
    private static final Pattern BASE64_PADDING = Pattern.compile("^([A-Za-z0-9\\-_+/]+)((?:%3D|=)*)$");

    /**
     * The innermost message of a token: field 1 is 1, and field 2 is the video id.
     */
    private static final Pattern TOKEN_VIDEO_ID = Pattern.compile("\u0008\u0001\u0012\u000b([A-Za-z0-9_-]{11})");

    private static final String TEMPLATE_SEPARATOR = ":";

    private static final byte[] ASK_PANEL_ID_BYTES = ASK_PANEL_ID.getBytes(StandardCharsets.US_ASCII);

    @Nullable
    private static volatile String templateVideoId;
    @Nullable
    private static volatile byte[] templatePanel;

    /**
     * The last built panel, before its selection entity key is replaced, and the key it got.
     */
    @Nullable
    private static byte[] lastBuiltPanel;
    @Nullable
    private static String lastSelectionEntityKey;
    private static int selectionEntityCount;

    private AskPanelCommand() {
    }

    /**
     * The command that shows the Ask panel of a video,
     * and the tokens of the requests that the panel can make first.
     */
    static final class Panel {
        final byte[] command;
        /**
         * Requests the welcome of the chat, which the panel requests when it shows.
         */
        @Nullable
        final String welcomeToken;
        /**
         * Requests the summary of the video, as the summarize suggestion does.
         */
        @Nullable
        final String summarizeToken;
        /**
         * Identifies the summarize suggestion in the command that its tap runs, or -1 if unknown.
         */
        final long summarizeSelectionId;

        Panel(byte[] command, @Nullable String welcomeToken, @Nullable String summarizeToken,
              long summarizeSelectionId) {
            this.command = command;
            this.welcomeToken = welcomeToken;
            this.summarizeToken = summarizeToken;
            this.summarizeSelectionId = summarizeSelectionId;
        }
    }

    /**
     * @return If the panel is the Ask panel, by its id.
     */
    static boolean isAskPanel(byte[] panel) {
        if (indexOf(panel, ASK_PANEL_ID_BYTES) < 0) {
            return false;
        }

        ProtoReader reader = new ProtoReader(panel);
        while (reader.next()) {
            if (reader.wireType != WIRE_TYPE_LENGTH_DELIMITED) {
                continue;
            }
            if (reader.field == PANEL_PANEL_IDENTIFIER_FIELD
                    && Arrays.equals(reader.value(), ASK_PANEL_ID_BYTES)) {
                return true;
            }
            if (reader.field == PANEL_IDENTIFIER_FIELD) {
                ProtoReader identifier = new ProtoReader(reader.value());
                while (identifier.next()) {
                    if (identifier.field == IDENTIFIER_TAG_FIELD
                            && identifier.wireType == WIRE_TYPE_LENGTH_DELIMITED
                            && Arrays.equals(identifier.value(), ASK_PANEL_ID_BYTES)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Keeps the Ask panel of a video, to build the Ask panel of other videos.
     * A panel without the summarize suggestion replaces no saved panel,
     * as the panels built from it show the welcome instead of the summary.
     */
    static void saveTemplate(byte[] panel) {
        final String videoId = findVideoId(latin1(panel), 0);
        if (videoId == null) {
            Logger.printDebug(() -> "Not saving Ask panel, its tokens hold no video id");
            return;
        }
        if (loadTemplate() && !hasSummarizeSuggestion(panel)) {
            Logger.printDebug(() -> "Not saving Ask panel without the summarize suggestion of video: " + videoId);
            return;
        }

        templateVideoId = videoId;
        templatePanel = panel;
        Settings.ASK_PANEL_TEMPLATE.save(videoId + TEMPLATE_SEPARATOR
                + Base64.encodeToString(panel, Base64.NO_WRAP));
        Logger.printDebug(() -> "Saved Ask panel of video: " + videoId + " size: " + panel.length);
    }

    static boolean hasTemplate() {
        return loadTemplate();
    }

    /**
     * @return The video of the saved Ask panel, or null if none is saved.
     */
    @Nullable
    static String getTemplateVideoId() {
        return loadTemplate() ? templateVideoId : null;
    }

    @Nullable
    static byte[] findFrameworkUpdates(byte[] response) {
        List<Field> fields = parse(response);
        if (fields == null) {
            return null;
        }
        for (Field field : fields) {
            if (field.number == RESPONSE_FRAMEWORK_UPDATES_FIELD && field.wireType == WIRE_TYPE_LENGTH_DELIMITED) {
                return field.value;
            }
        }
        return null;
    }

    @Nullable
    static byte[] findAskPanelInWatchPage(byte[] watchPage) {
        List<Field> fields = parse(watchPage);
        if (fields == null) {
            return null;
        }
        for (Field field : fields) {
            if (field.number != WATCH_PAGE_ENGAGEMENT_PANELS_FIELD || field.wireType != WIRE_TYPE_LENGTH_DELIMITED) {
                continue;
            }
            List<Field> engagementPanel = parse(field.value);
            if (engagementPanel == null) {
                continue;
            }
            for (Field renderer : engagementPanel) {
                if (renderer.number == ENGAGEMENT_PANEL_RENDERER_FIELD
                        && renderer.wireType == WIRE_TYPE_LENGTH_DELIMITED && isAskPanel(renderer.value)) {
                    return renderer.value;
                }
            }
        }
        return null;
    }

    /**
     * @return The Ask panel of the video above the page, or null if there is no panel to build it from.
     */
    @Nullable
    static Panel build(String videoId) {
        if (!loadTemplate()) {
            return null;
        }

        final String fromVideoId = templateVideoId;
        final byte[] fromPanel = templatePanel;
        if (fromVideoId == null || fromPanel == null) {
            return null;
        }

        int[] replacements = new int[1];
        byte[] panel = bytes(retarget(latin1(fromPanel), fromVideoId, videoId, 0, replacements));
        Logger.printDebug(() -> "Ask panel of video: " + videoId + " built from video: "
                + fromVideoId + " video id replacements: " + replacements[0]);
        if (replacements[0] == 0) {
            return null;
        }

        // The app keeps the suggestion chosen in a panel for the app session, by its entity key,
        // so a panel with the key of another panel shows no suggestions once one was chosen there.
        // A panel built again unchanged keeps its key, so the app shows its chat as it was.
        if (!Arrays.equals(panel, lastBuiltPanel)) {
            lastBuiltPanel = panel;
            lastSelectionEntityKey = newSelectionEntityKey();
        }
        panel = bytes(retarget(latin1(panel), SELECTION_ENTITY_KEY, lastSelectionEntityKey, 0, new int[1]));

        // The panel shows the suggestions of the video it was built from until the welcome
        // replaces them, so only the summarize suggestion is kept.
        byte[] summarizeOnly = keepSummarizeSuggestion(panel, 0);
        if (summarizeOnly != null) {
            panel = summarizeOnly;
        }
        String[] tokens = new String[2];
        long[] summarizeSelectionId = {-1};
        findTokens(panel, 0, tokens, summarizeSelectionId);

        ProtoWriter replacingPanel = new ProtoWriter();
        replacingPanel.writeRaw(panel);
        replacingPanel.writeVarint(PANEL_REPLACE_REGISTERED_FIELD, 1);

        ProtoWriter engagementPanel = new ProtoWriter();
        engagementPanel.writeLengthDelimited(ENGAGEMENT_PANEL_RENDERER_FIELD, replacingPanel.toByteArray());

        ProtoWriter identifier = new ProtoWriter();
        identifier.writeVarint(IDENTIFIER_SURFACE_FIELD, SURFACE_BROWSE);
        identifier.writeLengthDelimited(IDENTIFIER_TAG_FIELD, ASK_PANEL_ID_BYTES);

        ProtoWriter endpoint = new ProtoWriter();
        endpoint.writeLengthDelimited(ENGAGEMENT_PANEL_FIELD, engagementPanel.toByteArray());
        endpoint.writeLengthDelimited(IDENTIFIER_FIELD, identifier.toByteArray());

        ProtoWriter command = new ProtoWriter();
        command.writeLengthDelimited(SHOW_ENGAGEMENT_PANEL_ENDPOINT_FIELD, endpoint.toByteArray());
        return new Panel(command.toByteArray(), tokens[0], tokens[1], summarizeSelectionId[0]);
    }

    /**
     * @return A selection entity key of its own, as long as the key in the template,
     *         so the tokens that hold it keep their length.
     */
    private static String newSelectionEntityKey() {
        String number = Integer.toString(++selectionEntityCount);
        return SELECTION_ENTITY_KEY.substring(0, SELECTION_ENTITY_KEY.length() - number.length()) + number;
    }

    /**
     * @return If the panel shows the summarize suggestion, the message without the other suggestions,
     *         or null if no suggestion was removed.
     */
    @Nullable
    private static byte[] keepSummarizeSuggestion(byte[] message, int depth) {
        if (depth > MAX_MESSAGE_DEPTH) {
            return null;
        }
        List<Field> fields = parse(message);
        if (fields == null) {
            return null;
        }

        boolean hasSummarizeSuggestion = false;
        for (Field field : fields) {
            if (getSuggestionIcon(field) == SUGGESTION_ICON_SUMMARIZE) {
                hasSummarizeSuggestion = true;
                break;
            }
        }

        boolean changed = false;
        ProtoWriter result = new ProtoWriter();
        for (Field field : fields) {
            if (hasSummarizeSuggestion) {
                final int icon = getSuggestionIcon(field);
                if (icon >= 0 && icon != SUGGESTION_ICON_SUMMARIZE) {
                    changed = true;
                    continue;
                }
            }
            if (field.wireType == WIRE_TYPE_LENGTH_DELIMITED) {
                byte[] child = keepSummarizeSuggestion(field.value, depth + 1);
                if (child != null) {
                    result.writeLengthDelimited(field.number, child);
                    changed = true;
                    continue;
                }
            }
            result.writeRaw(field.raw);
        }
        return changed ? result.toByteArray() : null;
    }

    private static boolean hasSummarizeSuggestion(byte[] panel) {
        String[] tokens = new String[2];
        findTokens(panel, 0, tokens, new long[]{-1});
        return tokens[1] != null;
    }

    /**
     * @return The icon of the suggestion, or -1 if the field is not a suggestion.
     */
    private static int getSuggestionIcon(Field field) {
        if (field.wireType != WIRE_TYPE_LENGTH_DELIMITED) {
            return -1;
        }
        List<Field> suggestion = parse(field.value);
        if (suggestion == null) {
            return -1;
        }
        long icon = -1;
        boolean hasToken = false;
        for (Field suggestionField : suggestion) {
            if (suggestionField.number == SUGGESTION_ICON_FIELD
                    && suggestionField.wireType == WIRE_TYPE_VARINT) {
                icon = suggestionField.varint;
            } else if (suggestionField.number == SUGGESTION_TOKEN_FIELD
                    && suggestionField.wireType == WIRE_TYPE_LENGTH_DELIMITED) {
                hasToken = getAskRequest(latin1(suggestionField.value)) != null;
            }
        }
        return hasToken ? (int) icon : -1;
    }

    /**
     * Finds the welcome token, and the token and selection id of the summarize suggestion.
     *
     * @param tokens The welcome token and the summarize token, as they are found.
     * @param summarizeSelectionId The selection id of the summarize suggestion, as it is found.
     */
    private static void findTokens(byte[] message, int depth, String[] tokens, long[] summarizeSelectionId) {
        if (depth > MAX_MESSAGE_DEPTH) {
            return;
        }
        List<Field> fields = parse(message);
        if (fields == null) {
            return;
        }

        for (Field field : fields) {
            if (field.wireType != WIRE_TYPE_LENGTH_DELIMITED) {
                continue;
            }
            if (tokens[1] == null && getSuggestionIcon(field) == SUGGESTION_ICON_SUMMARIZE) {
                for (Field suggestionField : parse(field.value)) {
                    if (suggestionField.number == SUGGESTION_TOKEN_FIELD) {
                        tokens[1] = latin1(suggestionField.value);
                    } else if (suggestionField.number == SUGGESTION_SELECTION_ID_FIELD
                            && suggestionField.wireType == WIRE_TYPE_VARINT) {
                        summarizeSelectionId[0] = suggestionField.varint;
                    }
                }
                continue;
            }
            if (tokens[0] == null && field.value.length >= MIN_TOKEN_LENGTH) {
                String token = latin1(field.value);
                if (isWelcomeRequest(getAskRequest(token))) {
                    tokens[0] = token;
                    continue;
                }
            }
            findTokens(field.value, depth + 1, tokens, summarizeSelectionId);
        }
    }

    /**
     * @return If the command is the command of a suggestion tap that selects the suggestion.
     */
    static boolean selectsSuggestion(byte[] command, long selectionId) {
        ProtoWriter selection = new ProtoWriter();
        selection.writeVarint(1, selectionId);
        ProtoWriter message = new ProtoWriter();
        message.writeLengthDelimited(SELECTION_FIELD, selection.toByteArray());
        return indexOf(command, message.toByteArray()) >= 0;
    }

    private static boolean isWelcomeRequest(@Nullable List<Field> request) {
        if (request == null) {
            return false;
        }
        for (Field field : request) {
            if (field.number == REQUEST_TYPE_FIELD && field.wireType == WIRE_TYPE_LENGTH_DELIMITED) {
                List<Field> type = parse(field.value);
                if (type != null) {
                    for (Field typeField : type) {
                        if (typeField.number == REQUEST_TYPE_WELCOME_FIELD) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * @return The video id of an Ask chat request, or null if the token is not one.
     */
    @Nullable
    static String getVideoIdOfToken(String token) {
        List<Field> request = getAskRequest(token);
        if (request != null) {
            for (Field field : request) {
                if (field.number == REQUEST_VIDEO_ID_FIELD && field.wireType == WIRE_TYPE_LENGTH_DELIMITED) {
                    return latin1(field.value);
                }
            }
        }
        return null;
    }

    /**
     * @return The fields of the request of an Ask chat token, or null if the text is not one.
     */
    @Nullable
    private static List<Field> getAskRequest(String token) {
        if (token.length() < MIN_TOKEN_LENGTH) {
            return null;
        }
        byte[] tokenMessage = decodeToken(token);
        List<Field> tokenFields = tokenMessage == null ? null : parse(tokenMessage);
        if (tokenFields == null || tokenFields.size() != 1 || tokenFields.get(0).number != TOKEN_FIELD) {
            return null;
        }

        List<Field> wrapper = parse(tokenFields.get(0).value);
        if (wrapper == null) {
            return null;
        }
        byte[] request = null;
        boolean isAskPanel = false;
        for (Field field : wrapper) {
            if (field.wireType != WIRE_TYPE_LENGTH_DELIMITED) {
                continue;
            }
            if (field.number == TOKEN_PANEL_ID_FIELD) {
                isAskPanel = Arrays.equals(field.value, ASK_PANEL_ID_BYTES);
            } else if (field.number == TOKEN_REQUEST_FIELD) {
                request = decodeToken(latin1(field.value));
            }
        }
        if (!isAskPanel || request == null) {
            return null;
        }

        List<Field> requestFields = parse(request);
        if (requestFields == null || requestFields.isEmpty() || requestFields.get(0).number != REQUEST_FIELD) {
            return null;
        }
        return parse(requestFields.get(0).value);
    }

    /**
     * @return The form data without the playback position of the player, which is the position
     *         of another video when the panel is not the panel of the playing video,
     *         or null if the form data has no playback position.
     */
    @Nullable
    static byte[] removePlayerOffset(byte[] formData) {
        List<Field> fields = parse(formData);
        if (fields == null) {
            return null;
        }

        boolean changed = false;
        ProtoWriter result = new ProtoWriter();
        for (Field field : fields) {
            if (field.number == FORM_DATA_INPUT_COMPOSER_FIELD && field.wireType == WIRE_TYPE_LENGTH_DELIMITED) {
                List<Field> inputComposer = parse(field.value);
                if (inputComposer != null) {
                    ProtoWriter composer = new ProtoWriter();
                    for (Field composerField : inputComposer) {
                        if (composerField.number == INPUT_COMPOSER_PLAYER_OFFSET_FIELD) {
                            changed = true;
                        } else {
                            composer.writeRaw(composerField.raw);
                        }
                    }
                    result.writeLengthDelimited(field.number, composer.toByteArray());
                    continue;
                }
            }
            result.writeRaw(field.raw);
        }
        return changed ? result.toByteArray() : null;
    }

    @Nullable
    private static byte[] decodeToken(String token) {
        Matcher padding = BASE64_PADDING.matcher(token);
        return padding.matches() ? decodeBase64(padding.group(1)) : null;
    }

    /**
     * @return The fields of the message, or null if the data is not a message.
     */
    @Nullable
    static List<Field> parse(byte[] data) {
        List<Field> fields = new ArrayList<>();
        ProtoReader reader = new ProtoReader(data);
        while (reader.next()) {
            if (reader.field == 0) {
                return null;
            }
            fields.add(new Field(reader.field, reader.wireType,
                    reader.wireType == WIRE_TYPE_VARINT ? reader.varint : 0,
                    reader.value(), reader.raw()));
        }
        return reader.isMalformed ? null : fields;
    }

    static final class Field {
        final int number;
        final int wireType;
        final long varint;
        final byte[] value;
        /**
         * The tag and the value, as they are in the message.
         */
        final byte[] raw;

        Field(int number, int wireType, long varint, byte[] value, byte[] raw) {
            this.number = number;
            this.wireType = wireType;
            this.varint = varint;
            this.value = value;
            this.raw = raw;
        }
    }

    private static boolean loadTemplate() {
        if (templatePanel != null) {
            return true;
        }

        String saved = Settings.ASK_PANEL_TEMPLATE.get();
        final int separator = saved.indexOf(TEMPLATE_SEPARATOR);
        if (separator != VIDEO_ID_LENGTH) {
            return false;
        }

        try {
            templatePanel = Base64.decode(saved.substring(separator + 1), Base64.NO_WRAP);
            templateVideoId = saved.substring(0, separator);
            return true;
        } catch (IllegalArgumentException ex) {
            Logger.printException(() -> "Could not load the saved Ask panel", ex);
            Settings.ASK_PANEL_TEMPLATE.resetToDefault();
            return false;
        }
    }

    /**
     * @param data Bytes as a Latin-1 string, so each character is one byte.
     * @return The video id of the first token of the data, at any depth.
     */
    @Nullable
    private static String findVideoId(String data, int depth) {
        Matcher videoId = TOKEN_VIDEO_ID.matcher(data);
        if (videoId.find()) {
            return videoId.group(1);
        }
        if (depth >= MAX_TOKEN_DEPTH) {
            return null;
        }

        Matcher run = BASE64_RUN.matcher(data);
        while (run.find()) {
            Matcher padding = BASE64_PADDING.matcher(run.group());
            if (!padding.matches()) {
                continue;
            }
            String body = padding.group(1);
            for (int offset = 0; offset < 4 && body.length() - offset >= 16; offset++) {
                byte[] decoded = decodeBase64(body.substring(offset));
                if (decoded != null) {
                    String found = findVideoId(latin1(decoded), depth + 1);
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Replaces the video id in the data, and in every base64 run of the data, at any depth.
     *
     * @param data Bytes as a Latin-1 string, so each character is one byte.
     * @param replacements Counts the replaced video ids.
     */
    private static String retarget(String data, String fromVideoId, String toVideoId,
                                   int depth, int[] replacements) {
        if (depth > MAX_TOKEN_DEPTH) {
            return data;
        }

        Matcher matcher = BASE64_RUN.matcher(data);
        StringBuilder result = new StringBuilder(data.length());
        int end = 0;
        while (matcher.find()) {
            result.append(data, end, matcher.start());
            result.append(retargetRun(matcher.group(), fromVideoId, toVideoId, depth, replacements));
            end = matcher.end();
        }
        result.append(data, end, data.length());

        String retargeted = result.toString();
        for (int index = retargeted.indexOf(fromVideoId); index >= 0;
             index = retargeted.indexOf(fromVideoId, index + VIDEO_ID_LENGTH)) {
            replacements[0]++;
        }
        return retargeted.replace(fromVideoId, toVideoId);
    }

    /**
     * A base64 run can start with a byte of the message that holds it, such as a length
     * byte that is also a base64 character, and base64 aligns every 4 characters,
     * so each of the 4 first characters is tried as the start of the base64.
     * A token of the panel without padding can also be followed by such a byte, such as the tag of the next field,
     * so for the runs of the panel, up to 3 last characters are tried as not part of the base64 too.
     * Every start is tried with all the characters first, and the runs inside tokens keep all their characters,
     * as base64 that starts at the wrong character can also encode back the same,
     * and the runs it holds could then be retargeted with characters left out.
     */
    private static String retargetRun(String run, String fromVideoId, String toVideoId,
                                      int depth, int[] replacements) {
        Matcher padding = BASE64_PADDING.matcher(run);
        if (!padding.matches()) {
            return run;
        }
        String body = padding.group(1);
        String pad = padding.group(2);

        final int maxTrim = depth == 0 ? 3 : 0;
        for (int trim = 0; trim <= maxTrim; trim++) {
            for (int offset = 0; offset < 4; offset++) {
                String head = body.substring(0, offset);
                String tail = body.substring(offset, body.length() - trim);
                if (tail.length() < 16) {
                    break;
                }

                final byte[] decoded = decodeBase64(tail);
                // Base64 that ends with a byte after the token can decode too, but decoding can drop
                // the last bits of that byte, so only base64 that encodes back the same is used.
                if (decoded == null || !Base64.encodeToString(decoded, base64Flags(tail)).equals(tail)) {
                    continue;
                }

                final int previousReplacements = replacements[0];
                String retargeted = retarget(latin1(decoded), fromVideoId, toVideoId, depth + 1, replacements);
                if (replacements[0] == previousReplacements) {
                    continue;
                }

                String encoded = Base64.encodeToString(bytes(retargeted), base64Flags(tail));
                if (encoded.length() != tail.length()) {
                    // Not the base64 of the token, as a token keeps its length.
                    replacements[0] = previousReplacements;
                    continue;
                }
                return head + encoded + body.substring(body.length() - trim) + pad;
            }
        }

        return run;
    }

    private static int base64Flags(String base64) {
        return (base64.indexOf('+') >= 0 || base64.indexOf('/') >= 0
                ? Base64.DEFAULT
                : Base64.URL_SAFE) | Base64.NO_PADDING | Base64.NO_WRAP;
    }

    /**
     * @param base64 Characters of either base64 alphabet, without padding.
     * @return The decoded bytes, or null if the text is not base64.
     */
    @Nullable
    private static byte[] decodeBase64(String base64) {
        final boolean hasUrlSafeCharacters = base64.indexOf('-') >= 0 || base64.indexOf('_') >= 0;
        final boolean hasDefaultCharacters = base64.indexOf('+') >= 0 || base64.indexOf('/') >= 0;
        // A single character after the last group of 4 encodes no full byte.
        if (base64.length() % 4 == 1 || (hasUrlSafeCharacters && hasDefaultCharacters)) {
            return null;
        }
        return Base64.decode(base64, base64Flags(base64));
    }

    private static String latin1(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static byte[] bytes(String latin1) {
        return latin1.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0, last = haystack.length - needle.length; i <= last; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static final class ProtoReader {
        private final byte[] data;
        private int position;
        private int fieldStart;
        private int valueStart;
        private int valueEnd;
        int field;
        int wireType;
        long varint;

        ProtoReader(byte[] data) {
            this.data = data;
        }

        /**
         * If the data is not a message, which is found while reading it.
         */
        boolean isMalformed;

        /**
         * @return If a field was read. False at the end of the data, or if the data is not a message.
         */
        boolean next() {
            if (position >= data.length || isMalformed) {
                return false;
            }

            fieldStart = position;
            final long tag = readVarint();
            field = (int) (tag >>> 3);
            wireType = (int) (tag & 0x7);

            switch (wireType) {
                case WIRE_TYPE_VARINT -> {
                    valueStart = position;
                    varint = readVarint();
                }
                case WIRE_TYPE_FIXED64 -> {
                    valueStart = position;
                    position += 8;
                }
                case WIRE_TYPE_LENGTH_DELIMITED -> {
                    final long length = readVarint();
                    valueStart = position;
                    if (length < 0 || length > data.length - position) {
                        isMalformed = true;
                    } else {
                        position += (int) length;
                    }
                }
                case WIRE_TYPE_FIXED32 -> {
                    valueStart = position;
                    position += 4;
                }
                default -> isMalformed = true;
            }

            if (isMalformed || position > data.length) {
                isMalformed = true;
                return false;
            }
            valueEnd = position;
            return true;
        }

        byte[] value() {
            return Arrays.copyOfRange(data, valueStart, valueEnd);
        }

        byte[] raw() {
            return Arrays.copyOfRange(data, fieldStart, valueEnd);
        }

        private long readVarint() {
            long result = 0;
            for (int shift = 0; shift < 64 && position < data.length; shift += 7) {
                final byte b = data[position++];
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return result;
                }
            }
            isMalformed = true;
            return 0;
        }
    }

    static final class ProtoWriter {
        private final ByteArrayOutputStream stream = new ByteArrayOutputStream();

        void writeRaw(byte[] message) {
            stream.write(message, 0, message.length);
        }

        void writeVarint(int field, long value) {
            writeVarint(((long) field << 3) | WIRE_TYPE_VARINT);
            writeVarint(value);
        }

        void writeLengthDelimited(int field, byte[] value) {
            writeVarint(((long) field << 3) | WIRE_TYPE_LENGTH_DELIMITED);
            writeVarint(value.length);
            stream.write(value, 0, value.length);
        }

        private void writeVarint(long value) {
            while ((value & ~0x7FL) != 0) {
                stream.write((int) ((value & 0x7F) | 0x80));
                value >>>= 7;
            }
            stream.write((int) value);
        }

        byte[] toByteArray() {
            return stream.toByteArray();
        }
    }
}
