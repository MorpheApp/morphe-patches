package android.support.v7.widget;

import android.content.Context;
import android.view.View;

@SuppressWarnings("unused")
public class RecyclerView extends View {

    public RecyclerView(Context context) {
        super(context);
        throw new UnsupportedOperationException("Stub");
    }

    @SuppressWarnings("SameReturnValue")
    public View getChildAt(final int index) {
        return null;
    }

    @SuppressWarnings("SameReturnValue")
    public int getChildCount() {
        return 0;
    }

    @Override
    public int computeHorizontalScrollOffset() {
        return 0;
    }
}
