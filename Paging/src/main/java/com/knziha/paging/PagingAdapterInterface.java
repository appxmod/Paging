package com.knziha.paging;

import android.view.View;

public interface PagingAdapterInterface<T extends CursorReader> {
	int getCount();
	/** 获取数据的核心方法，在此触发分页
	 * @param  triggerPaging 控制是否触发分页。目前只允许在适配器过程中触发分页，否则我打你。*/
	T getReaderAt(int position, boolean triggerPaging);
	void close();
	/**  恢复列表位置的时候，有可能恢复到一个不存在（已删除）的位置，需要向上扫描 */
	void growUp(View recyclerView);
	void recheckBoundary();
	void recheckBoundaryAt(int position, boolean start);
	boolean getTopReached();
	int getPageIdx(int position);
}
