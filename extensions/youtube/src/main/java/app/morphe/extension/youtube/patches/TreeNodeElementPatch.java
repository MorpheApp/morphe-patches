/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.youtube.shared.ConversionContext.ELEMENT_IDENTIFIER_COMPONENT;
import static app.morphe.extension.youtube.shared.ConversionContext.ELEMENT_IDENTIFIER_LAZILY;

import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.shared.ConversionContext.ContextInterface;

@SuppressWarnings("unused")
public class TreeNodeElementPatch {

    /**
     * Injection point.
     */
    public static void onTreeNodeResultLoaded(ContextInterface contextInterface, List<Object> treeNodeResultList) {
        try {
            if (treeNodeResultList == null || treeNodeResultList.isEmpty()) {
                return;
            }
            String firstElement = treeNodeResultList.get(0).toString();
            if (ELEMENT_IDENTIFIER_COMPONENT.equals(firstElement)) {
                String path = contextInterface.patch_getPathBuilder().toString();
                onComponentLoaded(path, treeNodeResultList);
            } else if (ELEMENT_IDENTIFIER_LAZILY.equals(firstElement)) {
                String identifier = contextInterface.patch_getIdentifier();
                if (Utils.isNotEmpty(identifier)) {
                    onLazilyConvertedElementLoaded(identifier, treeNodeResultList);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onTreeNodeResultLoaded failure", ex);
        }
    }

    private static void onComponentLoaded(String path, List<Object> treeNodeResultList) {
        // Code added during patching.
    }

    private static void onLazilyConvertedElementLoaded(String identifier, List<Object> treeNodeResultList) {
        // Code added during patching.
    }
}