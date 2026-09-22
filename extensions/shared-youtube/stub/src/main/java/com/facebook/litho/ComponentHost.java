package com.facebook.litho;

import android.content.Context;
import android.view.ViewGroup;

public abstract class ComponentHost extends ViewGroup {
    public ComponentHost(Context context) {
        super(context);
    }

    public TextContent getTextContent() {
        throw new IllegalStateException("Stub");
    }
}
