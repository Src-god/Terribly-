package org.telegram.messenger.gramify;

import android.os.Parcel;
import android.os.Parcelable;

/** Ek song. (Parcelable = Telegram ke andar share/intent karna aasan ho jata hai.) */
public class Song implements Parcelable {

    public String id;
    public String title;
    public String artist;      // subtitle: "Pritam, Arijit Singh - Brahmastra"
    public String album;
    public String image;       // 500x500 thumbnail url
    public String language;
    public String year;
    public int durationSec;
    public String permaUrl;    // jiosaavn.com link
    public String streamUrl;   // resolved (decrypted) playable url
    public int bitrate = 320;

    public Song() {}

    protected Song(Parcel in) {
        id = in.readString();
        title = in.readString();
        artist = in.readString();
        album = in.readString();
        image = in.readString();
        language = in.readString();
        year = in.readString();
        durationSec = in.readInt();
        permaUrl = in.readString();
        streamUrl = in.readString();
        bitrate = in.readInt();
    }

    public static final Creator<Song> CREATOR = new Creator<Song>() {
        @Override
        public Song createFromParcel(Parcel in) { return new Song(in); }
        @Override
        public Song[] newArray(int size) { return new Song[size]; }
    };

    @Override
    public int describeContents() { return 0; }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(id);
        dest.writeString(title);
        dest.writeString(artist);
        dest.writeString(album);
        dest.writeString(image);
        dest.writeString(language);
        dest.writeString(year);
        dest.writeInt(durationSec);
        dest.writeString(permaUrl);
        dest.writeString(streamUrl);
        dest.writeInt(bitrate);
    }

    public String durationText() {
        if (durationSec <= 0) return "";
        final int m = durationSec / 60, s = durationSec % 60;
        return m + ":" + (s < 10 ? "0" : "") + s;
    }

    public String subtitle() {
        if (artist != null && !artist.isEmpty()) return artist;
        if (album != null && !album.isEmpty()) return album;
        return "";
    }

    @Override
    public String toString() {
        return (title == null ? "?" : title) + (artist == null ? "" : " — " + artist);
    }
}
