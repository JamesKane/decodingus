// `sqlx::migrate!` embeds the migrations directory at COMPILE time. Without this
// hint, adding/editing a .sql file does not rebuild du-db, so the embedded set
// goes stale and migrations silently fail to apply. Watch the directory so any
// change forces a recompile.
fn main() {
    println!("cargo:rerun-if-changed=../../migrations");
}
