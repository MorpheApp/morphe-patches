/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.subscriptiongroups;

import static app.morphe.extension.shared.StringRef.str;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Management dialog for subscription channel groups: create a group,
 * delete a group, and edit a group's members as one channel name per
 * line.
 *
 * Simplification, compared to ChannelWhitelistDialog: that dialog is a
 * bespoke bottom sheet built on SheetBottomDialog/CustomDialog. This
 * v1 uses a plain AlertDialog instead, which is much less code and is
 * good enough for text editing; the management flow (list -> create /
 * edit / delete -> save through the persistence class) mirrors the
 * whitelist dialog. All user-visible strings come from StringRef,
 * following the whitelist dialog convention.
 */
public final class SubscriptionGroupsDialog {

    private SubscriptionGroupsDialog() {
    }

    public static void show(Context context) {
        try {
            Utils.verifyOnMainThread();
            LinkedHashMap<String, LinkedHashSet<ChannelEntry>> groups = SubscriptionGroups.loadGroups();
            String[] groupNames = groups.keySet().toArray(new String[0]);

            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setTitle(str("morphe_subscription_groups_dialog_title"));
            if (groupNames.length == 0) {
                builder.setMessage(str("morphe_subscription_groups_dialog_empty"));
            } else {
                builder.setItems(groupNames, (dialog, which) ->
                        showEditGroup(context, groupNames[which]));
            }
            builder.setPositiveButton(str("morphe_subscription_groups_create"),
                    (dialog, which) -> showCreateGroup(context));
            if (groupNames.length > 0 && !SubscriptionGroups.loadKnownChannels().isEmpty()) {
                builder.setNeutralButton(str("morphe_subscription_groups_pick_known"),
                        (dialog, which) -> showKnownPickerGroups(context));
            }
            builder.setNegativeButton(str("morphe_subscription_groups_close"), null);
            builder.show();
        } catch (Exception ex) {
            Logger.printException(() -> "Subscription groups dialog failure", ex);
        }
    }

    private static void showCreateGroup(Context context) {
        EditText nameInput = new EditText(context);
        nameInput.setHint(str("morphe_subscription_groups_group_name_hint"));
        nameInput.setInputType(InputType.TYPE_CLASS_TEXT);
        nameInput.setSingleLine(true);

        new AlertDialog.Builder(context)
                .setTitle(str("morphe_subscription_groups_create"))
                .setView(wrapWithPadding(context, nameInput))
                .setPositiveButton(str("morphe_subscription_groups_save"), (dialog, which) -> {
                    String name = nameInput.getText().toString();
                    if (!SubscriptionGroups.createGroup(name)) {
                        Utils.showToastShort(str("morphe_subscription_groups_group_exists"));
                        return;
                    }
                    // Continue straight into editing the new group's members.
                    showEditGroup(context, name.trim());
                })
                .setNegativeButton(str("morphe_subscription_groups_cancel"), null)
                .show();
    }

    private static void showEditGroup(Context context, String groupName) {
        LinkedHashMap<String, LinkedHashSet<ChannelEntry>> groups = SubscriptionGroups.loadGroups();
        LinkedHashSet<ChannelEntry> members = SubscriptionGroupFilter.findGroup(groups, groupName);
        if (members == null) {
            show(context);
            return;
        }

        EditText membersInput = new EditText(context);
        membersInput.setHint(str("morphe_subscription_groups_members_hint"));
        membersInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        membersInput.setMinLines(6);
        membersInput.setText(SubscriptionGroupFilter.joinMemberLines(members));
        // Place the cursor at the end, after the existing names.
        membersInput.setSelection(membersInput.getText().length());

        new AlertDialog.Builder(context)
                .setTitle(groupName)
                .setView(wrapWithPadding(context, membersInput))
                .setPositiveButton(str("morphe_subscription_groups_save"), (dialog, which) -> {
                    SubscriptionGroups.setGroupMembers(groupName,
                            SubscriptionGroupFilter.resolveMemberLines(
                                    membersInput.getText().toString(),
                                    members,
                                    SubscriptionGroups.loadKnownChannels()));
                    Utils.showToastShort(str("morphe_subscription_groups_saved"));
                })
                .setNeutralButton(str("morphe_subscription_groups_delete"), (dialog, which) -> {
                    SubscriptionGroups.deleteGroup(groupName);
                    Utils.showToastShort(str("morphe_subscription_groups_saved"));
                })
                .setNegativeButton(str("morphe_subscription_groups_cancel"), null)
                .show();
    }

    /** First step of the known-channels picker: choose which group to edit. */
    private static void showKnownPickerGroups(Context context) {
        LinkedHashMap<String, LinkedHashSet<ChannelEntry>> groups = SubscriptionGroups.loadGroups();
        String[] groupNames = groups.keySet().toArray(new String[0]);
        new AlertDialog.Builder(context)
                .setTitle(str("morphe_subscription_groups_pick_known"))
                .setItems(groupNames, (dialog, which) ->
                        showKnownPicker(context, groupNames[which]))
                .setNegativeButton(str("morphe_subscription_groups_cancel"), null)
                .show();
    }

    /**
     * Multi-choice list of the channels recorded from channel pages.
     * Checked channels become the group's members; members that are
     * not in the known list are kept as they are.
     */
    private static void showKnownPicker(Context context, String groupName) {
        LinkedHashMap<String, LinkedHashSet<ChannelEntry>> groups = SubscriptionGroups.loadGroups();
        LinkedHashSet<ChannelEntry> members = SubscriptionGroupFilter.findGroup(groups, groupName);
        if (members == null) {
            show(context);
            return;
        }
        List<ChannelEntry> known = SubscriptionGroups.loadKnownChannels();
        String[] labels = new String[known.size()];
        boolean[] checked = new boolean[known.size()];
        for (int i = 0; i < known.size(); i++) {
            labels[i] = known.get(i).displayName();
            checked[i] = members.contains(known.get(i));
        }
        new AlertDialog.Builder(context)
                .setTitle(groupName)
                .setMultiChoiceItems(labels, checked,
                        (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(str("morphe_subscription_groups_save"), (dialog, which) -> {
                    LinkedHashSet<ChannelEntry> updated = new LinkedHashSet<>();
                    // Keep members the known list does not know about.
                    for (ChannelEntry member : members) {
                        if (!known.contains(member)) {
                            updated.add(member);
                        }
                    }
                    for (int i = 0; i < known.size(); i++) {
                        if (checked[i]) {
                            updated.add(known.get(i));
                        }
                    }
                    SubscriptionGroups.setGroupMembers(groupName, updated);
                    Utils.showToastShort(str("morphe_subscription_groups_saved"));
                })
                .setNegativeButton(str("morphe_subscription_groups_cancel"), null)
                .show();
    }

    private static LinearLayout wrapWithPadding(Context context, EditText input) {
        float density = context.getResources().getDisplayMetrics().density;
        int padding = Math.round(16 * density);
        LinearLayout layout = new LinearLayout(context);
        layout.setPadding(padding, padding / 2, padding, 0);
        layout.addView(input);
        return layout;
    }
}
