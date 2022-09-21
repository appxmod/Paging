package com.knziha.paging;

import android.database.Cursor;

public interface CursorReaderMultiSortNum extends CursorReader{
	void ReadCursor(Cursor cursor, long rowID, long[] sortNums);
}
