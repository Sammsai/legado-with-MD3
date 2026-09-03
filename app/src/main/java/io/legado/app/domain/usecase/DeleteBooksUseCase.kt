package io.legado.app.domain.usecase

import io.legado.app.domain.gateway.BookSourceCallbackGateway
import io.legado.app.domain.gateway.LocalBookGateway
import io.legado.app.domain.repository.BookDomainRepository
import io.legado.app.help.AppWebDav
import io.legado.app.help.config.LocalConfig

class DeleteBooksUseCase(
    private val bookRepository: BookDomainRepository,
    private val localBookGateway: LocalBookGateway,
    private val bookSourceCallbackGateway: BookSourceCallbackGateway
) {

    suspend fun execute(bookUrls: Set<String>, deleteOriginal: Boolean): List<String> {
        if (bookUrls.isEmpty()) return emptyList()
        val books = bookRepository.getDeletableBooks(bookUrls)
        books.forEach { book ->
            LocalConfig.recordBookDeleted(book.bookUrl)
            if (book.isLocal) {
                localBookGateway.deleteBook(book.bookUrl, deleteOriginal)
            } else {
                bookSourceCallbackGateway.onDeleteFromShelf(book.bookUrl)
            }
            bookRepository.deleteChaptersByBook(book.bookUrl)
        }
        bookRepository.deleteBooks(books.map { it.bookUrl }.toSet())
        AppWebDav.triggerBookshelfSync()
        return books.map { it.bookUrl }
    }
}
