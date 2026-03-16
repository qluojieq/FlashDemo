package com.brandon.flashdemo;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import java.io.IOException;
import java.util.ArrayList;

public class PhotoActivity extends AppCompatActivity {
    private static final String TAG = "PhotoActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_photo);

        ArrayList<String> photoPaths = getIntent().getStringArrayListExtra("photo_paths");
        if (photoPaths == null) {
            photoPaths = new ArrayList<>();
        }

        ViewPager2 viewPager = findViewById(R.id.viewPager);
        PhotoAdapter adapter = new PhotoAdapter(photoPaths);
        viewPager.setAdapter(adapter);
    }

    private class PhotoAdapter extends RecyclerView.Adapter<PhotoAdapter.ViewHolder> {
        private final ArrayList<String> photoPaths;

        public PhotoAdapter(ArrayList<String> photoPaths) {
            this.photoPaths = photoPaths;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_photo, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            String pathOrUri = photoPaths.get(position);
            try {
                Bitmap bitmap;
                if (pathOrUri.startsWith("content://")) {
                    bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), Uri.parse(pathOrUri));
                } else {
                    bitmap = BitmapFactory.decodeFile(pathOrUri);
                }
                holder.imageView.setImageBitmap(bitmap);
            } catch (IOException e) {
                Log.e(TAG, "Error loading image", e);
            }
        }

        @Override
        public int getItemCount() {
            return photoPaths.size();
        }

        public class ViewHolder extends RecyclerView.ViewHolder {
            ImageView imageView;

            public ViewHolder(@NonNull View itemView) {
                super(itemView);
                imageView = itemView.findViewById(R.id.imageView);
            }
        }
    }
}
