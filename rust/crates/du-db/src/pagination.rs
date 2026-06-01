//! Pagination helper shared by list/search queries.

use serde::Serialize;

/// A page of results plus the totals the UI needs to render pagination controls.
#[derive(Debug, Clone, Serialize)]
pub struct Page<T> {
    pub items: Vec<T>,
    pub total: i64,
    pub page: i64,
    pub page_size: i64,
}

impl<T> Page<T> {
    pub fn total_pages(&self) -> i64 {
        if self.page_size <= 0 {
            0
        } else {
            (self.total + self.page_size - 1) / self.page_size
        }
    }

    /// Clamp page/page_size to sane bounds and return the SQL OFFSET.
    pub fn offset(page: i64, page_size: i64) -> i64 {
        (page.max(1) - 1) * page_size.clamp(1, 200)
    }
}
