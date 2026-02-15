/**
 * Copyright 2026 Morphe.
 * https://github.com/morpheapp/morphe-patches
 *
 * File-Specific License Notice (GPLv3 Section 7 Terms)
 *
 * This file is part of the Morphe patches project and is licensed under
 * the GNU General Public License version 3 (GPLv3), with the Additional
 * Terms under Section 7 described in the Morphe patches LICENSE file.
 *
 * https://www.gnu.org/licenses/gpl-3.0.html
 *
 * Trademark Notice (GPLv3 Section 7(e)):
 * ----------------------------------------------------
 * This file programmatically renders the Morphe logo.
 *
 * “Morphe” and associated logos are trademarks and copyrighted
 * works owned by Morphe. No rights under trademark law are
 * granted by this license.
 *
 * Distribution or modification of this file does not grant
 * permission to use the Morphe name, logo, or branding in a
 * manner that implies endorsement, affiliation, or official status.
 *
 * For official branding assets and usage guidelines, see:
 * https://github.com/MorpheApp/morphe-branding
 *
 * Distribution and Derivative Works:
 * ----------------------------------
 * This comment block MUST be preserved in all copies, distributions,
 * and derivative works of this file, whether in source or modified
 * form.
 *
 * All other terms of the Morphe Patches LICENSE, including Section 7c
 * (Project Name Restriction) and the GPLv3 itself, remain fully
 * applicable to this file.
 */

package app.morphe.extension.reddit.ui;

import static androidx.core.graphics.PathParser.createPathFromPathData;

import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.PathShape;

public class MorpheSettingsIconVectorDrawable {
    private static final Path PATH_START =
            createPathFromPathData("M4.716,3.50031 C2.84895,3.50031,2.50031,4.91697,2.50031,5.76697 L2.50031,18.347 C2.55522,19.5369,3.34356,20.5003,4.49674,20.5003 C5.64992,20.5003,6.63835,19.537,6.69327,18.347 L6.69327,10.697 C8.23084,12.5103,9.30955,15.627,12.0003,15.7403 C15.7934,15.7403,17.9174,6.59783,21.5003,5.96401 C21.4703,4.73698,21.0961,3.50031,19.284,3.50031 C17.417,3.50031,15.8991,6.33364,14.9656,7.5803 C14.0321,8.82696,13.5928,10.187,12.0003,10.187 C10.4078,10.187,9.9136,8.77034,9.03499,7.58034 C8.10146,6.33364,6.58305,3.50031,4.716,3.50031 Z");
    private static final Path PATH_END =
            createPathFromPathData("M17.3067,15.8 L17.3067,18.347 C17.3616,19.537,18.35,20.5003,19.5032,20.5003 C20.6564,20.5003,21.4453,19.537,21.5002,18.347 L21.5002,9.2 C21.5002,7.8742,17.3067,13.9316,17.3067,15.8 Z");
    private static final int INTRINSIC_SIZE_PX = (int) (100 * Resources.getSystem().getDisplayMetrics().density);
    private static final float VIEW_PORT_SIZE = 24f; // viewportWidth, viewportHeight

    public static Drawable getIcon() {
        Drawable[] layers = {getShapeDrawable(PATH_START), getShapeDrawable(PATH_END)};
        return new LayerDrawable(layers);
    }

    private static ShapeDrawable getShapeDrawable(Path path) {
        PathShape pathShape = new PathShape(path, VIEW_PORT_SIZE, VIEW_PORT_SIZE);
        ShapeDrawable strokeDrawable = new ShapeDrawable(pathShape);
        Paint strokePaint = strokeDrawable.getPaint();
        strokePaint.setColor(Color.BLACK);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(1.8f);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setAntiAlias(true);
        strokeDrawable.setIntrinsicWidth(INTRINSIC_SIZE_PX);
        strokeDrawable.setIntrinsicHeight(INTRINSIC_SIZE_PX);

        return strokeDrawable;
    }
}