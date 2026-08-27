package com.studykit.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.studykit.data.entity.Book
import com.studykit.data.entity.BookReview
import com.studykit.data.entity.Excerpt
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Query("SELECT * FROM books ORDER BY started_at DESC")
    fun observeAll(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE id = :id")
    fun observeById(id: Long): Flow<Book?>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getById(id: Long): Book?

    @Insert
    suspend fun insert(book: Book): Long

    @Update
    suspend fun update(book: Book)

    @Query("UPDATE books SET current_page = :page WHERE id = :id")
    suspend fun updateProgress(id: Long, page: Int)

    @Query("UPDATE books SET status = 'finished', finished_at = :finishedAt, current_page = total_pages WHERE id = :id")
    suspend fun markFinished(id: Long, finishedAt: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM excerpts")
    fun observeExcerptCount(): Flow<Int>

    @Query("SELECT * FROM excerpts WHERE book_id = :bookId ORDER BY created_at DESC")
    fun observeExcerpts(bookId: Long): Flow<List<Excerpt>>

    @Query("SELECT * FROM excerpts WHERE id = :id")
    fun observeExcerpt(id: Long): Flow<Excerpt?>

    @Query("SELECT * FROM excerpts WHERE id = :id")
    suspend fun getExcerpt(id: Long): Excerpt?

    @Insert
    suspend fun insertExcerpt(excerpt: Excerpt): Long

    @Update
    suspend fun updateExcerpt(excerpt: Excerpt)

    @Delete
    suspend fun deleteExcerpt(excerpt: Excerpt)

    @Query("SELECT * FROM book_reviews WHERE book_id = :bookId ORDER BY at DESC")
    fun observeReviews(bookId: Long): Flow<List<BookReview>>

    @Query("SELECT * FROM book_reviews WHERE id = :id")
    fun observeReview(id: Long): Flow<BookReview?>

    @Query("SELECT * FROM book_reviews WHERE id = :id")
    suspend fun getReview(id: Long): BookReview?

    @Insert
    suspend fun insertReview(review: BookReview): Long

    @Update
    suspend fun updateReview(review: BookReview)
}
