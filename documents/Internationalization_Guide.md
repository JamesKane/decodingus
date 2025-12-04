# Internationalization (I18n) Guide for Decoding Us

## Overview
Currently, the application has English text embedded directly into Twirl templates. To support multiple languages, we should adopt the **Standard Play Framework I18n Pattern**. This approach is robust, performant, and natively supported by the framework without requiring external libraries.

## The Design Pattern

The core concept is to separate **Content** (text) from **Structure** (HTML/Twirl).

### 1. Architecture
*   **Message Files:** Text is stored in `conf/messages` (default/English), `conf/messages.fr` (French), `conf/messages.es` (Spanish), etc.
*   **Key-Value Pairs:** Each line in these files maps a unique key to a translated string.
    *   `home.title = Welcome to Decoding Us`
*   **Twirl Templates:** Instead of hardcoded text, templates use the `Messages` object to look up strings by key.
    *   `<h1>@messages("home.title")</h1>`
*   **Context Propagation:** Controllers inject `MessagesControllerComponents` and mix in `I18nSupport` to automatically detect the user's preferred language (via `Accept-Language` header or cookies) and pass the correct `Messages` provider to the view.

## Implementation Steps

### Step 1: Create Message Files
Create the `conf/messages` file for the default language (English).

**File:** `conf/messages`
```properties
# General
app.name = Decoding Us
site.title = Decoding Us - Citizen Science Genetics

# Navigation
nav.home = Home
nav.about = About
nav.contact = Contact

# Home Page
home.welcome = Welcome to Decoding Us
home.intro = Decoding Us will be a next-generation platform for citizen science...
home.goals.title = The system shall be architected with these goals:
```

### Step 2: Update Configuration
Enable the languages in `conf/application.conf`.

```hocon
play.i18n {
  # The list of supported languages
  langs = [ "en", "fr", "es" ]
}
```

### Step 3: Refactor Controllers
Update controllers to provide `Messages` support. This is often done by injecting `MessagesControllerComponents`.

**Example:**
```scala
import play.api.mvc._
import play.api.i18n._
import javax.inject.Inject

class HomeController @Inject()(cc: MessagesControllerComponents) extends AbstractController(cc) with I18nSupport {
  def index = Action { implicit request =>
    // 'request' implicitly contains the messages context due to I18nSupport
    Ok(views.html.index())
  }
}
```

### Step 4: Refactor Views
Update Twirl templates to accept an implicit `Messages` provider and use it.

**File:** `app/views/index.scala.html`
```scala
@()(implicit messages: Messages) 

@main(messages("site.title")) {
    <main class="container">
        <h1>@messages("home.welcome")</h1>
        <p>@messages("home.intro")</p>
    </main>
}
```

**File:** `app/views/main.scala.html` (Layout)
```scala
@(title: String)(content: Html)(implicit messages: Messages)

<!DOCTYPE html>
<html lang="@messages.lang.code">
    <head>
        <title>@title</title>
    </head>
    <body>
        <!-- Pass messages implicitly to sub-components -->
        @_navbar() 
        @content
    </body>
</html>
```

## Handling Dynamic Content
For text that includes dynamic values (e.g., "Hello, John"), use placeholders in the message file.

`conf/messages`:
```properties
greeting = Hello, {0}!
```

Twirl:
```scala
@messages("greeting", userName)
```

## Advantages
1.  **Standardization:** Any Play developer will instantly understand this structure.
2.  **Performance:** Message lookups are extremely fast and compiled.
3.  **Type Safety:** While the keys are strings, the integration with Twirl is robust.
4.  **Flexibility:** Adding a new language just requires adding a new `messages.xx` file.
