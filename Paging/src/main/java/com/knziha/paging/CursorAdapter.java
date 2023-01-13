package com.knziha.paging;

import android.database.Cursor;
import android.view.View;

public class CursorAdapter<T extends CursorReader> implements PagingAdapterInterface<T> {
	final Cursor cursor;
	final T reader;
	
	public CursorAdapter(Cursor cursor, T reader) {
		this.cursor = cursor;
		this.reader = reader;
	}
	
	@Override
	public int getCount() {
		return cursor.getCount();
	}
	
	@Override
	public T getReaderAt(int position, boolean triggerPaging) {
		cursor.moveToPosition(position);
		reader.ReadCursor(this, cursor, -1, 0);
		return reader;
	}
	
	@Override
	public void close() {
		cursor.close();
	}
	
	@Override
	public void growUp(View recyclerView) {
	
	}
	
	@Override
	public void recheckBoundary() {
	
	}
	
	@Override
	public void recheckBoundaryAt(int i, boolean start) {
	
	}
	
	@Override
	public boolean getTopReached() {
		return true;
	}
	
	@Override
	public int getPageIdx(int position) {
		return 0;
	}
}