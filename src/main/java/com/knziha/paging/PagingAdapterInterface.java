package com.knziha.paging;

public interface PagingAdapterInterface<T extends CursorReader> {
	int getCount();
	T getReaderAt(int position);
}
