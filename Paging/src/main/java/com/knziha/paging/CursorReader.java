package com.knziha.paging;

import android.database.Cursor;

public interface CursorReader {
	void ReadCursor(PagingAdapterInterface adapter, Cursor cursor, long rowID, long sortNum);
}
