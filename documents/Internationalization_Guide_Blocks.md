# Internationalization (I18n) Guide: Block-Based Content Strategy

## Overview
While the standard key-value pair approach (Property Files) is excellent for UI labels and short text, it is cumbersome and unmaintainable for long-form content like "About Us" pages, blog posts, or extensive privacy policies.

For these cases, we recommend a **Block-Based Content Strategy** that treats long-form content as structural dependencies rather than simple strings.

## Recommended Pattern: Localized Partial Views

Instead of putting entire paragraphs into a `messages` file, we create separate Twirl templates (partials) for the content blocks of each language.

### 1. Architecture

*   **Structure:** Maintain the main page structure (layout, headers, footers) in a master template.
*   **Content Blocks:** Create a directory structure for localized content fragments.
    *   `app/views/content/en/aboutBody.scala.html`
    *   `app/views/content/es/aboutBody.scala.html`
*   **Dispatcher:** Use a helper (or the controller) to dynamically select the correct partial based on the user's language.

### 2. Implementation

#### File Structure
```
app/
  views/
    about.scala.html           (Master structure)
    content/
      en/
        aboutText.scala.html   (English paragraphs)
      es/
        aboutText.scala.html   (Spanish paragraphs)
```

#### The Content Partial (English)
**`app/views/content/en/aboutText.scala.html`**
```html
<p>Decoding Us will be a next-generation platform for citizen science focused on empowering individuals...</p>
<p>The system shall be architected with these goals:</p>
<ul>
    <li><strong>Federated Design:</strong>...</li>
</ul>
```

#### The Master View (Dispatcher)
**`app/views/about.scala.html`**
```scala
@()(implicit messages: Messages)

@main(messages("nav.about")) {
    <div class="container">
        <h1>@messages("nav.about")</h1> <!-- Keep short titles in messages file -->
        
        @messages.lang.code match {
            case "es" => { @views.html.content.es.aboutText() }
            case "fr" => { @views.html.content.fr.aboutText() }
            case _    => { @views.html.content.en.aboutText() } <!-- Default to English -->
        }
    </div>
}
```

### 3. Alternative: Markdown-Based Content

For even easier editing (especially for non-developers), you can store long-form content as **Markdown** files and render them at runtime.

*   **Storage:** `conf/content/about/en.md`, `conf/content/about/es.md`.
*   **Loader:** A simple service reads the file based on the requested language.
*   **Renderer:** Use a library like `flexmark-java` to convert Markdown to HTML in the controller, then pass the `Html` object to the view.

**Controller Example:**
```scala
def about = Action { implicit request =>
    val lang = messagesApi.preferred(request).lang.code
    val markdownContent = contentLoader.load("about", lang) // returns "## About Us..."
    val htmlContent = MarkdownRenderer.render(markdownContent)
    Ok(views.html.about(htmlContent))
}
```

## Summary Recommendation

| Use Case | Recommended Pattern |
| :--- | :--- |
| **UI Labels, Buttons, Short Titles** | **Standard `messages` file** (Key-Value). |
| **Static Long-Form (About, Terms)** | **Localized Partial Views** (Twirl). Best for compile-time safety. |
| **Dynamic/Frequent Long-Form (Blog)** | **Markdown Files**. Best for ease of editing and CMS-like behavior. |

For the MVP "About" page, **Localized Partial Views** offers the best balance of type safety and maintainability without introducing new dependencies.
