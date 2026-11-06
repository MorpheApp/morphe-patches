/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4175
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.os.Build;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.EnumSetting;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class ChangePipActionsPatch {

    /**
     * Actions handled by the picture-in-picture broadcast receiver of the app.
     */
    private static final String YOUTUBE_ACTION_PREFIX = "com.google.android.youtube.action.";

    private static final String REWIND_ACTION = "app.morphe.extension.youtube.PIP_REWIND";
    private static final String FAST_FORWARD_ACTION = "app.morphe.extension.youtube.PIP_FAST_FORWARD";

    /**
     * Matches the 10 seconds shown by the rewind and fast forward icons.
     */
    private static final long SEEK_LENGTH_MILLISECONDS = 10_000;

    public enum PipAction {
        DEFAULT,
        PREVIOUS("quantum_ic_skip_previous_white_24", "playback_control_previous", YOUTUBE_ACTION_PREFIX + "pip.prev"),
        NEXT("quantum_ic_skip_next_white_24", "playback_control_next", YOUTUBE_ACTION_PREFIX + "pip.next"),
        REWIND("quantum_ic_replay_10_white_24", "accessibility_rewind", REWIND_ACTION),
        FAST_FORWARD("quantum_ic_forward_10_white_24", "accessibility_fast_forward", FAST_FORWARD_ACTION),
        PLAY_PAUSE,
        BACKGROUND_PLAYBACK("quantum_ic_headset_white_24", "playback_control_background_playback", YOUTUBE_ACTION_PREFIX + "background");

        @Nullable
        private final String iconName;
        @Nullable
        private final String titleName;
        @Nullable
        private final String intentAction;
        @Nullable
        private RemoteAction remoteAction;

        PipAction() {
            this(null, null, null);
        }

        PipAction(@Nullable String iconName, @Nullable String titleName, @Nullable String intentAction) {
            this.iconName = iconName;
            this.titleName = titleName;
            this.intentAction = intentAction;
        }

        private RemoteAction getRemoteAction() {
            if (remoteAction == null) {
                Context context = Utils.getContext();
                String title = context.getString(ResourceUtils.getIdentifierOrThrow(ResourceType.STRING, titleName));
                PendingIntent intent = PendingIntent.getBroadcast(
                        context,
                        0,
                        new Intent(intentAction).setPackage(context.getPackageName()),
                        PendingIntent.FLAG_IMMUTABLE
                );
                remoteAction = new RemoteAction(
                        Icon.createWithResource(context, ResourceUtils.getIdentifierOrThrow(ResourceType.DRAWABLE, iconName)),
                        title,
                        title,
                        intent
                );
            }
            return remoteAction;
        }
    }

    /**
     * Settings of the left, center and right actions.
     */
    private static final List<EnumSetting<PipAction>> ACTION_SETTINGS = List.of(
            Settings.PIP_ACTION_LEFT,
            Settings.PIP_ACTION_CENTER,
            Settings.PIP_ACTION_RIGHT
    );

    /**
     * Index of the play/pause action. The app changes it to replay or retry if needed.
     */
    private static final int PLAY_PAUSE_INDEX = 1;

    private static final BroadcastReceiver seekReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                String action = intent.getAction();
                if (REWIND_ACTION.equals(action)) {
                    VideoInformation.seekToRelative(-SEEK_LENGTH_MILLISECONDS);
                } else if (FAST_FORWARD_ACTION.equals(action)) {
                    VideoInformation.seekToRelative(SEEK_LENGTH_MILLISECONDS);
                }
            } catch (Exception ex) {
                Logger.printException(() -> "onReceive failure", ex);
            }
        }
    };

    private static boolean seekReceiverRegistered;

    @SuppressLint("UnspecifiedRegisterReceiverFlag") // The flags only exist with Android 13 and newer.
    private static void registerSeekReceiver() {
        if (seekReceiverRegistered) {
            return;
        }
        seekReceiverRegistered = true;

        IntentFilter filter = new IntentFilter();
        filter.addAction(REWIND_ACTION);
        filter.addAction(FAST_FORWARD_ACTION);

        Context context = Utils.getContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(seekReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(seekReceiver, filter);
        }
    }

    /**
     * Injection point.
     *
     * @param actions The left, center and right actions. Fewer actions are used
     *                while the autoplay countdown or only the play/pause action is shown.
     */
    public static List<RemoteAction> getActions(List<RemoteAction> actions) {
        try {
            if (actions.size() != ACTION_SETTINGS.size()) {
                return actions;
            }

            List<RemoteAction> modifiedActions = null;
            for (int i = 0, size = ACTION_SETTINGS.size(); i < size; i++) {
                PipAction action = ACTION_SETTINGS.get(i).get();
                if (action == PipAction.DEFAULT) {
                    continue;
                }

                if (modifiedActions == null) {
                    modifiedActions = new ArrayList<>(actions);
                }

                if (action == PipAction.PLAY_PAUSE) {
                    modifiedActions.set(i, actions.get(PLAY_PAUSE_INDEX));
                } else {
                    if (action == PipAction.REWIND || action == PipAction.FAST_FORWARD) {
                        registerSeekReceiver();
                    }
                    modifiedActions.set(i, action.getRemoteAction());
                }
            }

            return modifiedActions == null
                    ? actions
                    : modifiedActions;
        } catch (Exception ex) {
            Logger.printException(() -> "getActions failure", ex);
            return actions;
        }
    }
}
