# Automaton

All automatons projects are an independant set of features. They're built in the library mindset more than the framework mindset. Meaning that the assembly of features is the library's user responsability and no inversion of control is allowed in automatons.

# KISS

Keep It Simple and Stupid:

- So landing pages should be in pure html css,
- Javascript is kept where needed,
- Clojurescript is used where more complicated features are needed (like calling backend REST API in a robust way)

# Factorization

As all design decisions, factorizations are compromises :

## Languages

In landing website, pages are shipped in english and french, the duplication is authorized. The rationale is that factorization is not so simple, factorization is breaking html toolings, and website could also be different when language is.

## Headers, footers, menus, ...

For some pages components, like headers, footers, menus, SEO, code fragments of html and css are authorized. The babahska's pipeline will copy all of that fragments in html pages, in specific markups.

## Components

For complex components, react component will be factorized and developped in pure react/clojurescript to have the full control of the DOM.

# Backend

* backend is used to serve REST API.
* for a start, landing pages are served as resources of the web server. They should be servable like a static website, compatible with cdn.

# Structure
  
## Website

Fragments are stored under `resources/fragments` directory. Babashka's pipeline copy that fragments in the.

Pages are stored under `resources/public` directory,

Fragments are named with the language in it. For instance, header is `header.fr.html`.

Pages are named in directories with the language`fr`. So each subdirectory is a valid local website, navigable and viewable directly with local tools.

A babashka's task named `update-website` is updating all pages with fragments. For instance, `header.fr.html` fragment is updating all pages in `fr` subdirectory, all `html` files in it are updated, the code between two specific comments `<!-- BEGIN:HEADER -->`, `<!-- END:HEADER -->` is replaced with the fragment file.
