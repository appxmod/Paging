package com.knziha.paging;

import static androidx.recyclerview.widget.LinearLayoutManager.INVALID_OFFSET;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.drawable.Drawable;
import android.util.Pair;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.knziha.logger.CMN;
import com.knziha.paging.AppIconCover.AppIconCover;
import com.knziha.paging.AppIconCover.AppLoadableBean;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static com.knziha.logger.CMN.EmptyGlideDrawable;


public class PagingCursorAdapter<T extends CursorReader> implements PagingAdapterInterface<T> {
	private RecyclerView recyclerView;
	public static boolean simulateSlowIO;
	public long id;
	
	ArrayList<SimpleCursorPage<T>> pages =  new ArrayList<>(1024);
	Queue<Pair<Integer, SimpleCursorPage<T>>> insertQueue = new ConcurrentLinkedQueue<>();
	Queue<SimpleCursorPage<T>> dataQueue = new ConcurrentLinkedQueue<>();
	Queue<SimpleCursorPage<T>> updateQueue = new ConcurrentLinkedQueue<>();
	AtomicInteger dataQueue_size = new AtomicInteger();
	
	int number_of_rows_detected;
	final ConstructorInterface<T> mRowConstructor;
	final ConstructorInterface<T[]> mRowArrConstructor;
	final SQLiteDatabase db;
	
	private ImageView pageAsyncLoader;
	private boolean glide_initialized = true;
	private RequestBuilder<Drawable> glide;
	
	private int pageSz = 20;
	
	private String sortField;
	private String dataFields;
	private String table;
	private boolean DESC = true;
	
	private String whereClause = StringUtils.EMPTY;
	private String[] whereArgs = ArrayUtils.EMPTY_STRING_ARRAY;
	
	private String sql, sql_reverse, sql_fst;
	
	final static boolean debugging = false;
	
	boolean closed;
	private boolean topReached;
	
	public PagingCursorAdapter(SQLiteDatabase db, ConstructorInterface<T> mRowConstructor
			, ConstructorInterface<T[]> mRowArrConstructor) {
		this.mRowConstructor = mRowConstructor;
		this.mRowArrConstructor = mRowArrConstructor;
		this.db = db;
	}
	
	@Override
	public int getCount() {
		return number_of_rows_detected;
	}
	
	public int getRealCount() {
		int number_of_rows_detected=0;
		for (SimpleCursorPage page:pages) {
			number_of_rows_detected += page.number_of_row;
		}
		if(number_of_rows_detected!=this.number_of_rows_detected) {
			CMN.Log("Error!!!");
			for (SimpleCursorPage page:pages) {
				CMN.Log(page);
			}
		}
		return number_of_rows_detected;
	}
	
	int pageDataSz = 100;
	
	@Override
	public T getReaderAt(int position, boolean triggerPaging) {
		int idx = getPageAt(position);
		SimpleCursorPage<T> page = pages.get(idx);
		int offsetedPos = (int) (position-basePosOffset);
//		if (debugging) {
//			CMN.Log("getReaderAt basePosOffset="+basePosOffset
//					, (position+1)+"/"+getCount()/*+"=="+getRealCount()*/, "@"+(idx+1)+"/"+pages.size(), basePosOffset+page.pos, basePosOffset+page.end);
//			CMN.Log("--- "+page);
//			CMN.Log("--- "+offsetedPos, page.rows[(int) (offsetedPos-page.pos)]);
//		}
		if(glide==null || glide_initialized) {
			boolean b1=idx==pages.size()-1 && offsetedPos >=page.end-page.number_of_row/2;
			boolean b2=idx==0 && offsetedPos<=page.pos+page.number_of_row/2;
			// b1 是向下扫
//			if (b1 ^ b2) {
//				PrepareNxtPage(page, b1);
//			}
//			else if (b1 && b2) {
//				PrepareNxtPage(page, false);
//			}
			
			if ((b1 || b2) && mGrowingPage != page) {
				mGrowingPageDir = 0;
			}
			if(b1) PrepareNxtPage(page, b1);
			if(b2) PrepareNxtPage(page, false);
		}
		T ret;
		try {
			if (page.rows==null) {
				ret = null;
				if(debugging) CMN.Log("重新加载数据……");
				if (glide!=null) {
					init_glide();
					glide.load(new AppIconCover(new PageAsyncLoaderBean(number_of_rows_detected, page, false)))
							.into(getPageAsyncLoader(true));
				} else {
					ReLoadPage(page);
					ret = page.rows[(int) (offsetedPos-page.pos)];
				}
			} else {
				ret = page.rows[(int) (offsetedPos-page.pos)];
			}
		} catch (Exception e) {
			ret = null;
			CMN.Log(e);
		}
		if(dataQueue_size.get()>pageDataSz) {
			SimpleCursorPage<T> toRemove;
			int toRemoveCnt = dataQueue.size()-pageDataSz;
			int removed = 0;
			while((toRemove=dataQueue.poll())!=null && toRemoveCnt>0) {
				if (toRemove.rows!=null) {
					toRemove.rows = null;
					toRemoveCnt--;
				}
				removed++;
			}
			dataQueue_size.addAndGet(-removed);
		}
		return ret;
	}
	
	@Override
	public void close() {
		closed = true;
		recyclerView = null;
	}
	
	public PagingCursorAdapter<T> bindTo(RecyclerView recyclerView) {
		this.recyclerView = recyclerView;
		return this;
	}
	
	public PagingCursorAdapter<T> setAsyncLoader(Context context, ImageView pageAsyncLoader) {
		this.glide = Glide.with(context).load((String)null);
		this.pageAsyncLoader = pageAsyncLoader;
		glide_initialized = false;
		return this;
	}
	
	public PagingCursorAdapter<T> sortBy(String table, String sortField, boolean desc, String dataFields) {
		this.table = table;
		this.sortField = sortField;
		this.dataFields = dataFields;
		this.DESC = desc;
		glide_initialized = false;
		return this;
	}
	
	public PagingCursorAdapter<T> where(String whereClause, String[] whereArgs) {
		if (whereArgs==null) {
			whereArgs = ArrayUtils.EMPTY_STRING_ARRAY;
		}
		this.whereArgs = whereArgs;
		if (whereClause!=null) {
			this.whereClause = " and ("+whereClause+")";
		} else {
			this.whereClause = "";
		}
		glide_initialized = false;
		return this;
	}
	
	private void remakeSql() {
		sql = "SELECT ROWID," + sortField + "," + dataFields
				+ " FROM " + table + " WHERE " + sortField + (DESC?"<=?":">=?") + whereClause
				+ " ORDER BY " + sortField + " " + (DESC?"DESC":"ASC")  + " LIMIT " + pageSz;
		
		sql_reverse = "SELECT ROWID," + sortField + "," + dataFields
				+ " FROM " + table + " WHERE " + sortField + (!DESC?"<=?":">=?") + whereClause
				+ " ORDER BY " + sortField + " " + (!DESC?"DESC":"ASC")  + " LIMIT " + pageSz;
		
		sql_fst = "SELECT ROWID," + sortField
				+ " FROM " + table + " WHERE " + sortField + (DESC?"<=?":">=?") + whereClause
				+ " ORDER BY " + sortField + " " + (DESC?"DESC":"ASC")  + " LIMIT " + pageSz;
	}
	
	public interface OnLoadListener {
		void onLoaded(PagingAdapterInterface adapter);
	}
	
	public void startPaging(long resume_to_sort_number, long offset, int init_load_size, int page_size, OnLoadListener onload) {
		pages.clear();
		number_of_rows_detected = 0;
		pageSz = page_size;
		topReached = true; // xx
		if (glide!=null) {
			glide_initialized = false;
			glide.load(new AppIconCover(() -> {
				//try { Thread.sleep(200); } catch (InterruptedException ignored) { }
				//CMN.Log("startPaging::loading::");
				startPagingInternal(resume_to_sort_number, init_load_size);
				//recyclerView.getAdapter().postDataSetChanged(recyclerView, 120);
				return EmptyGlideDrawable;
			}))
			.override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL)
			.skipMemoryCache(true)
			.diskCacheStrategy(DiskCacheStrategy.NONE)
			.listener(new RequestListener<Drawable>() {
				@Override
				public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
					CMN.Log(e);
					return false;
				}
				@Override
				public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
					//CMN.Log("startPaging::onResourceReady::");
					if(closed) return true;
					int voff= (int) offset;
					if (voff!=INVALID_OFFSET && pages.size()!=0) {
						try {
							if (pages.get(0).st_fd!=resume_to_sort_number) {
								voff = 0;
							}
							if (recyclerView.getLayoutManager() instanceof LinearLayoutManager) {
								((LinearLayoutManager)recyclerView.getLayoutManager()).scrollToPositionWithOffset(0, voff);
							} else {
								recyclerView.scrollToPosition(0);
							}
						} catch (Exception ignored) { }
					}
					recyclerView.getAdapter().notifyDataSetChanged();
					init_glide();
					if (onload!=null) {
						onload.onLoaded(PagingCursorAdapter.this);
					}
					return true;
				}
			})
			.into(getPageAsyncLoader(true));
		} else {
			startPagingInternal(resume_to_sort_number, init_load_size);
		}
	}
	
	public void startPagingInternal(long resume_to_sort_number, int init_load_size) {
		pages.clear();
		remakeSql();
		number_of_rows_detected = 0;
		PreparePageAt(init_load_size, resume_to_sort_number);
	}
	
	public void mGrowRunnableRun(final boolean dir) {
		if(closed) return;
		final int st = number_of_rows_detected;
		final long stoff = basePosOffset;
		final SimpleCursorPage<T> pg = GrowPage(dir);
		if (pg!=null) {
			number_of_rows_detected += pg.number_of_row;
			if (!dir) {
				pages.add(0, pg);
				basePosOffset += pg.number_of_row;
			} else {
				pages.add(pg);
			}
			if (debugging) {
				if (!dir) CMN.Log("reverse GrowPage::", st, number_of_rows_detected - st, "pg.number_of_row="+pg.number_of_row, "pg.pos="+pg.pos);
				CMN.Log("basePosOffset=", stoff+"=>"+basePosOffset);
			}
			//if (number_of_rows_detected!=st) {
			//	recyclerView.getAdapter().notifyItemRangeInserted(dir?st+1:0, number_of_rows_detected - st);
			//}
		}
	}
	
	protected Runnable mGrowRunnableDown = new Runnable() {
		@Override
		public void run() {
			if(closed) return;
			final int st = number_of_rows_detected;
			mGrowRunnableRun(true);
			if (number_of_rows_detected!=st) {
				recyclerView.getAdapter().notifyItemRangeInserted(st+1, number_of_rows_detected - st);
			}
		}
	};
	
	protected Runnable mGrowRunnableUp = new Runnable() {
		@Override
		public void run() {
			if(closed) return;
			final int st = number_of_rows_detected;
			mGrowRunnableRun(false);
			if (number_of_rows_detected!=st) {
				recyclerView.getAdapter().notifyItemRangeInserted(0, number_of_rows_detected - st);
			}
		}
	};
	
	@Override
	public void growUp(View recyclerView) {
		recyclerView.post(mGrowRunnableUp);
	}
	
	class PageAsyncLoaderBean implements AppLoadableBean {
		final int st;
		final SimpleCursorPage<T> page;
		final boolean dir;
		PageAsyncLoaderBean(int st, SimpleCursorPage<T> page, boolean dir) {
			this.st = st;
			this.page = page;
			this.dir = dir;
		}
		@Override
		public Drawable load() {
			if(closed) return null;
			if(debugging) CMN.Log("PrepareNxtPage :: load!!! hasPage=", page!=null);
			if(debugging) CMN.Log("PageAsyncLoaderBean :: this="+Integer.toHexString(CMN.id(this)));
			if (page!=null) {
				ReLoadPage(page);
				updateQueue.add(page);
			} else {
				SimpleCursorPage<T> inserted = GrowPage(dir);
				//if (!dir) CMN.Log("PrepareNxtPage :: reverse GrowPage::", inserted);
				if (inserted!=null) {
					insertQueue.add(new Pair<>(dir?st+1:0, inserted));
				}
			}
			if(debugging) CMN.Log("PageAsyncLoaderBean loaded:: this="+Integer.toHexString(CMN.id(this)));
			return EmptyGlideDrawable;
		}
		@Override
		public boolean equals(@Nullable Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			PageAsyncLoaderBean other = ((PageAsyncLoaderBean) o);
			return dir==other.dir && page==other.page;
		}
		@Override
		public int hashCode() {
			return Objects.hash(page, dir);
		}
	}
	
	private void PrepareNxtPage(SimpleCursorPage<T> page, boolean dir) {
		if(closed) return;
		//CMN.Log("PrepareNxtPage::???", dir, mGrowingPage);
		if (!glide_initialized || recyclerView!=null && (mGrowingPage!=page || (mGrowingPageDir&(dir?2:1))==0)) {
			//CMN.Log("PrepareNxtPage::", dir, page);
			if (glide!=null) {
				mGrowingPage = page;
				//CMN.Log("PrepareNxtPage::glide loading...", dir, page);
				init_glide();
				glide.load(new AppIconCover(new PageAsyncLoaderBean(number_of_rows_detected, null, dir)))
						.into(getPageAsyncLoader(dir));
			} else {
				mGrowingPage = page;
				Runnable run = dir ? mGrowRunnableDown : mGrowRunnableUp;
				recyclerView.removeCallbacks(run);
				recyclerView.post(run);
			}
			mGrowingPageDir |= dir?2:1;
		}
	}
	
	private void init_glide() {
		if (!glide_initialized) {
			pageAsyncLoader.setTag(null);
			glide = glide.listener(new RequestListener<Drawable>() {
				@Override
				public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
					mGrowingPage = null;
					return false;
				}
				@Override
				public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
					//CMN.Log("startPaging::onResourceReady::!!!");
					if(closed) return true;
					Pair<Integer, SimpleCursorPage<T>> rng;
					SimpleCursorPage<T> pg;
					RecyclerView.Adapter ada = recyclerView.getAdapter();
					while((rng = insertQueue.poll())!=null) {
						pg = rng.second; //todo 避免重复加载？
						number_of_rows_detected += pg.number_of_row;
						if (rng.first==0) {
							pages.add(0, pg);
							basePosOffset += pg.number_of_row;
						} else {
							pages.add(pg);
						}
						if(debugging) CMN.Log("loaded::", rng.first, pg.number_of_row, pg, "detected="+number_of_rows_detected);
						ada.notifyItemRangeInserted(rng.first, pg.number_of_row);
					}
					SimpleCursorPage<T> page;
					while((page = updateQueue.poll())!=null) {
						ada.notifyItemRangeChanged((int)(page.pos+basePosOffset), (int) (page.end+basePosOffset));
					}
					return true;
				}
			});
			glide_initialized = true;
		}
	}
	
	public int reduce(int position,int start,int end) {//via mdict-js
		int len = end-start;
		if (len > 1) {
			len = len >> 1;
			return position - pages.get(start + len - 1).end >0
					? reduce(position,start+len,end)
					: reduce(position,start,start+len);
		} else {
			return start;
		}
	}
	
	private int getPageAt(int position) {
		//PreparePageAt(position, 0);
		return reduce((int) (position-basePosOffset), 0, pages.size());
	}
	
	boolean finished = false;
	
	SimpleCursorPage<T> mGrowingPage;
	int mGrowingPageDir;
	long basePosOffset;
	
	public void ReLoadPage(SimpleCursorPage<T> page) {
		String[] _whereArgs = this.whereArgs;
		String[] whereArgs = new String[1+_whereArgs.length];
		whereArgs[0] = page.st_fd+"";
		if (whereArgs.length>1) {
			System.arraycopy(_whereArgs, 0, whereArgs, 1, whereArgs.length - 1);
		}
		try (Cursor cursor = db.rawQuery(sql, whereArgs)){
			int len = cursor.getCount();
			if (len>0) {
				ArrayList<T> rows = new ArrayList<>(pageSz);
				//if (!dir && cursor.moveToLast()) cursor.moveToPrevious();
				int cc=0;
				long id = -1;
				long sort_number = 0;
				while (cursor.moveToNext() && cc< page.number_of_row) {
					id = cursor.getLong(0);
					sort_number = cursor.getLong(1);
					T row = mRowConstructor.newInstance(0);
					row.ReadCursor(this, cursor, id, sort_number);
					rows.add(row);
					cc++;
				}
				page.rows = rows.toArray(mRowArrConstructor.newInstance(rows.size()));
				dataQueue.add(page);
				dataQueue_size.incrementAndGet();
			}
		} catch (Exception e) {
			CMN.Log(e);
			throw new RuntimeException(e);
		}
	}
	
	public SimpleCursorPage<T> GrowPage(boolean dir) {
		SimpleCursorPage<T> lastPage = pages.size()==0?null:dir?pages.get(pages.size()-1):pages.get(0);
		if(lastPage==null) {
			lastPage = new SimpleCursorPage<>();
			lastPage.st_id = lastPage.ed_id = -1;
			if (DESC) {
				lastPage.st_fd = Long.MIN_VALUE;
				lastPage.ed_fd = Long.MAX_VALUE;
			} else {
				lastPage.st_fd = Long.MAX_VALUE;
				lastPage.ed_fd = Long.MIN_VALUE;
			}
			lastPage.pos = 0;
			lastPage.end = -1;
		}
		if(debugging) CMN.Log("GrowPage::", dir, lastPage);
		boolean popData = true;
		SimpleCursorPage<T> ret=null;
		String[] _whereArgs = this.whereArgs;
		String[] whereArgs = new String[1+_whereArgs.length];
		whereArgs[0] = (dir?lastPage.ed_fd:lastPage.st_fd)+"";
		if (whereArgs.length>1) {
			System.arraycopy(_whereArgs, 0, whereArgs, 1, whereArgs.length - 1);
		}
		if(debugging) CMN.Log("sql::", dir?sql:sql_reverse);
		try (Cursor cursor = db.rawQuery(dir?sql:sql_reverse, whereArgs)){
			int len = cursor.getCount();
			if(debugging) CMN.Log("sql::", len);
			if (len>0) {
				ArrayList<T> rows = null;
				long lastEndId = dir?lastPage.ed_id:lastPage.st_id;
				SimpleCursorPage<T> page = new SimpleCursorPage<>();
				boolean lastRowFound = false;
				long id = -1;
				long sort_number = 0;
				//if (!dir && cursor.moveToLast()) cursor.moveToPrevious();
				while (cursor.moveToNext()) {
					id = cursor.getLong(0);
					sort_number = cursor.getLong(1);
					if (lastEndId!=-1) {
						if (lastEndId==id) {
							lastEndId=-1;
							lastRowFound = true;
							if (page.number_of_row>0) {
								if(rows!=null) rows.clear();
								page.number_of_row=0;
							}
							continue;
						}
					}
					if (page.number_of_row==0) {
						if (dir) {
							page.st_fd = sort_number;
							page.st_id = id;
						} else {
							page.ed_fd = sort_number;
							page.ed_id = id;
						}
					}
					if (popData) {
						if (rows==null) {
							rows = new ArrayList<>(pageSz);
						}
						T row = mRowConstructor.newInstance(0);
						row.ReadCursor(this, cursor, id, sort_number);
						rows.add(row);
					}
					page.number_of_row++;
				}
				if ((!lastRowFound || id==lastEndId) && lastEndId!=-1 || lastRowFound && page.number_of_row==0 && cursor.getCount()==pageSz) {
					if (len==1) return null;
					throw new IllegalStateException("pageSz too small!"+lastRowFound+" "+rows.size()+" "+dir);
				}
				if (page.number_of_row == 0) {
					return null;
				}
				if (popData) {
					page.rows = rows.toArray(mRowArrConstructor.newInstance(rows.size()));
				}
				if (dir) {
					page.ed_fd = sort_number;
					page.ed_id = id;
					page.pos = lastPage.end + 1;
					page.end = lastPage.end + page.number_of_row;
					//pages.add(page);
				} else {
					page.st_fd = sort_number;
					page.st_id = id;
					page.pos = lastPage.pos - page.number_of_row;
					page.end = lastPage.pos - 1;
					//basePosOffset += page.number_of_row;
					//pages.add(0, page);
					if (popData) {
						ArrayUtils.reverse(page.rows);
					}
				}
				simulateSlowIO();
				dataQueue.add(page);
				dataQueue_size.incrementAndGet();
				ret = page;
				//number_of_rows_detected += page.number_of_row;
			}
		} catch (Exception e) {
			CMN.Log(e);
			throw new RuntimeException(e);
		}
		return ret;
	}
	
	private void simulateSlowIO() {
		if (simulateSlowIO) {
			CMN.Log("GrowPage::simulateSlowIO !!!");
			try {
				Thread.sleep(2000);
			} catch (InterruptedException ignored) { }
			CMN.Log("GrowPage::simulateSlowIO ...");
		}
	}
	
	public void PreparePageAt(int position, long resume) {
		if (!finished && position>=number_of_rows_detected) {
			if(debugging) CMN.Log("PreparePageAt::", position, number_of_rows_detected);
			SimpleCursorPage<T> lastPage;
			if (pages.size()>0) {
				lastPage = pages.get(pages.size()-1);
			} else {
				lastPage = new SimpleCursorPage<>();
				if (!DESC) {
					lastPage.ed_fd = Long.MIN_VALUE;
				}
				if (resume!=0) {
					lastPage.ed_fd = resume;
				}
			}
			while(true) {
				if(debugging) CMN.Log("PreparePageAt::sql="+sql);
				if(debugging) CMN.Log("PreparePageAt::number_of_rows_detected="+number_of_rows_detected, "pageSz="+pages.size(), "ed_fd::", lastPage.ed_fd);
				long st_pos = number_of_rows_detected;
				boolean popData = st_pos + pageSz > position;
				popData = true;
				String[] _whereArgs = this.whereArgs;
				String[] whereArgs = new String[1+_whereArgs.length];
				whereArgs[0] = lastPage.ed_fd+"";
				if (whereArgs.length>1) {
					System.arraycopy(_whereArgs, 0, whereArgs, 1, whereArgs.length - 1);
				}
				if(debugging) CMN.Log("PreparePageAt::whereArgs=", whereArgs);
				try (Cursor cursor = db.rawQuery(popData?sql:sql_fst, whereArgs)){
					int len = cursor.getCount();
					if (len>0) {
						ArrayList<T> rows = new ArrayList<>(pageSz);
						long lastEndId = lastPage.ed_id;
						SimpleCursorPage<T> page = new SimpleCursorPage<>();
						boolean lastRowFound = false;
						long id = -1;
						long sort_number = 0;
						while (cursor.moveToNext()) {
							if(debugging) CMN.Log("PreparePageAt::cursor.moveToNext()", (cursor.getPosition()+1)+"/"+len, "lastEndId="+lastEndId, lastRowFound);
							id = cursor.getLong(0);
							sort_number = cursor.getLong(1);
							if (lastEndId!=-1) {
								if (lastEndId==id) {
									lastEndId=-1;
									lastRowFound = true;
									if (page.number_of_row>0) {
										rows.clear();
										page.number_of_row=0;
									}
									continue;
								}
							}
							if (page.number_of_row==0) {
								page.st_fd = sort_number;
								page.st_id = id;
							}
							if (popData) {
								T row = mRowConstructor.newInstance(0);
								row.ReadCursor(this, cursor, id, sort_number);
								rows.add(row);
							}
							page.number_of_row++;
						}
						if ((!lastRowFound || id==lastEndId) && lastEndId!=-1 || lastRowFound && page.number_of_row==0 && cursor.getCount()==pageSz) {
							//todo dont throw!!!
							throw new IllegalStateException("pageSz too small!");
						}
						page.ed_fd = sort_number;
						page.ed_id = id;
						page.pos = st_pos;
						page.end = st_pos + page.number_of_row - 1;
						if (popData) {
							page.rows = rows.toArray(mRowArrConstructor.newInstance(rows.size()));
						}
						simulateSlowIO();
						number_of_rows_detected += page.number_of_row;
						pages.add(lastPage = page);
						finished |= len<pageSz;
					} else {
						finished |= true;
					}
					if (finished || number_of_rows_detected>position) {
						break;
					}
				} catch (Exception e) {
					CMN.Log(e);
					throw new RuntimeException(e);
				}
			}
			if (debugging) {
				CMN.Log("PreparePageAt::done::", position, number_of_rows_detected);
				for (SimpleCursorPage page:pages) {
					CMN.Log(page);
				}
			}
		}
	}
	
	@Override
	public void recheckBoundary() {
		mGrowingPageDir = 0;
	}
	
	@Override
	public void recheckBoundaryAt(int position, boolean start) {
		try {
			if(debugging) CMN.Log("recheckBoundaryAt::1::", position, start);
			if (pages.size() == 0) {
				PrepareNxtPage(null, true);
			} else {
				int idx = getPageAt(position);
				if(start && idx==0)
					getReaderAt(0, true);
				if(!start && idx==pages.size()-1)
					getReaderAt(getCount()-1, true);
			}
		} catch (Exception e) {
			CMN.Log(e);
		}
	}
	
	public ImageView getPageAsyncLoader(boolean dir) {
		return pageAsyncLoader;
//		if (dir) {
//			return pageAsyncLoader;
//		}
//		if(pageAsyncLoader.getTag()==null) {
//			pageAsyncLoader.setTag(new ImageView(pageAsyncLoader.getContext()));
//		}
//		return (ImageView) pageAsyncLoader.getTag();
	}
	
	@Override
	public boolean getTopReached() {
		return topReached;
	}
	
	@Override
	public int getPageIdx(int position) {
		return 0;
	}
}