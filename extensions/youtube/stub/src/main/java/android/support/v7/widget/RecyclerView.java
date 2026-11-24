package android.support.v7.widget;

import android.content.Context;
import android.view.ViewGroup;

@SuppressWarnings("unused")
public class RecyclerView extends ViewGroup {

    public RecyclerView(Context context) {
        super(context);
        throw new UnsupportedOperationException("Stub");
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
    }

    @Override
    public int computeHorizontalScrollOffset() {
        return 0;
    }
}
