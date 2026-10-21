package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** Draws the tvOS style lighter band behind the first (dock) row and adds a gap below it. */
public class DockDecoration extends RecyclerView.ItemDecoration {

    private final int dockSize;
    private final float density;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final AppAdapter adapter;
    private final boolean advancedEffects;

    /** Extra band below the tiles for their names (always_show_names), in px. */
    private final float namesRoom;

    /**
     * @param translucent the cinematic shelf's band: the artwork and the glow show through it.
     * @param names       the dock tiles show their names, so the band reaches down over them.
     */
    DockDecoration(int dockSize, float density, boolean translucent, boolean names,
                   AppAdapter adapter, boolean advancedEffects) {
        this.dockSize = dockSize;
        this.density = density;
        this.namesRoom = names ? 22 * density : 0;
        this.adapter = adapter;
        this.advancedEffects = advancedEffects;
        band.setColor(translucent ? 0x8C121014 : 0xFF141416);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(density);
        edge.setColor(translucent ? 0x26FFFFFF : 0x1FFFFFFF);
    }

    DockDecoration(int dockSize, float density, boolean translucent, boolean names) {
        this(dockSize, density, translucent, names, null, false);
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        if (pos >= 0 && pos < dockSize) {
            outRect.top = (int) (6 * density);
            outRect.bottom = (int) (12 * density);
        }
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        boolean any = false;
        float cardW = 0, cardH = 0;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (pos < 0 || pos >= dockSize) continue;
            RecyclerView.ViewHolder vh = parent.getChildViewHolder(child);
            View card = vh instanceof AppAdapter.Holder ? ((AppAdapter.Holder) vh).card : child.findViewById(R.id.card);
            if (card == null) continue;
            cardW = card.getWidth();
            cardH = card.getHeight();
            float l = child.getLeft() + card.getLeft();
            float t = child.getTop() + card.getTop() + child.getTranslationY();
            float r = l + cardW;
            float b = t + cardH;
            if (!any) {
                rect.set(l, t, r, b);
                any = true;
            } else {
                rect.union(l, t, r, b);
            }
        }
        if (!any || cardW <= 0) return;

        float scale = adapter != null ? adapter.focusScale() : (advancedEffects ? 1.12f : 1.10f);
        // Expansion of a focused card beyond its layout bounds
        float deltaX = cardW * (scale - 1f) / 2f;
        float deltaY = cardH * (scale - 1f) / 2f;
        float upwardShift = (advancedEffects && scale > 1f) ? 4f * density : 0f;

        // Card corner radius when scaled
        float cardRadius = AppAdapter.cornerRadius((int) cardW, density) * scale;
        float margin = 10f * density;

        // Symmetrically expand the dock band so the scaled/translated cards fit with uniform margin
        rect.left = (rect.left - deltaX) - margin;
        rect.right = (rect.right + deltaX) + margin;
        rect.top = (rect.top - deltaY - upwardShift) - margin;
        rect.bottom = (rect.bottom + deltaY) + margin + namesRoom;

        // Ensure the dock band stays within parent canvas boundaries
        if (rect.left < density) rect.left = density;
        if (rect.right > parent.getWidth() - density) rect.right = parent.getWidth() - density;

        // Concentric corner radius matching the scaled card corner
        float radius = cardRadius + margin;
        c.drawRoundRect(rect, radius, radius, band);
        c.drawRoundRect(rect, radius, radius, edge);
    }
}
