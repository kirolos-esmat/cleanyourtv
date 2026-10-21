package com.gathin.tvhome;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

final class WallpaperPickerAdapter extends RecyclerView.Adapter<WallpaperPickerAdapter.ViewHolder> {

    static final class Item {
        final String key;
        final String title;
        final String subtitle;
        final String thumbSource;

        Item(String key, String title, String subtitle, String thumbSource) {
            this.key = key;
            this.title = title;
            this.subtitle = subtitle;
            this.thumbSource = thumbSource;
        }
    }

    interface OnItemClickListener {
        void onItemClick(Item item);
    }

    private final Context context;
    private final List<Item> items;
    private final String selectedKey;
    private final OnItemClickListener listener;

    WallpaperPickerAdapter(Context context, List<Item> items, String selectedKey, OnItemClickListener listener) {
        this.context = context;
        this.items = items;
        this.selectedKey = selectedKey;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_wallpaper_card, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Item item = items.get(position);
        holder.title.setText(item.title);
        holder.subtitle.setText(item.subtitle);

        boolean isSelected = item.key.equals(selectedKey);
        holder.itemView.setSelected(isSelected);

        if ("none".equals(item.key)) {
            holder.thumb.setImageDrawable(null);
            holder.thumb.setBackgroundColor(0xFF101010);
        } else if (item.thumbSource != null && !item.thumbSource.isEmpty()) {
            WallpaperThumbnailLoader.load(context, item.thumbSource, holder.thumb);
        } else {
            holder.thumb.setImageDrawable(null);
            holder.thumb.setBackgroundColor(0xFF222222);
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onItemClick(item);
        });

        holder.itemView.setOnKeyListener((v, keyCode, event) -> {
            if ((keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER)
                    && event.getAction() == android.view.KeyEvent.ACTION_UP) {
                if (listener != null) listener.onItemClick(item);
                return true;
            }
            return false;
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final TextView title;
        final TextView subtitle;

        ViewHolder(View itemView) {
            super(itemView);
            thumb = itemView.findViewById(R.id.thumbImage);
            title = itemView.findViewById(R.id.cardTitle);
            subtitle = itemView.findViewById(R.id.cardSubtitle);
        }
    }
}
