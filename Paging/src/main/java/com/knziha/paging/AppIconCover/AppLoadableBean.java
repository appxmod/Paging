package com.knziha.paging.AppIconCover;

import android.graphics.drawable.Drawable;

import java.io.IOException;

public interface AppLoadableBean {
	Drawable load() throws IOException;
}
