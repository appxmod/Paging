package com.knziha.paging;

public class MultiFieldSortCursorPage<T extends CursorReader> extends SimpleCursorPage<T>{
	long[] st_fds;
	long[] ed_fds;
}
