package com.knziha.paging;

public interface PagingAdapterInterface<T extends CursorReader> {
	int getCount();
	/** 获取数据的核心方法，在此触发分页 */
	T getReaderAt(int position);
	void close();
	void recheckBoundary();
	void recheckBoundaryAt(int position, boolean start);
}
