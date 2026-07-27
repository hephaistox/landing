(ns landing.agora.document.storage "A storage implements a generic efficient storage for documents")

(defprotocol DocumentStorage
  ;; "Returns the document matching `id`"
   (fetch-id [_this id])
   ;; "Returns the document with latest minor of `TNLR`"
   (fetch-latest-revision [_this id])
   ;; "Documents of `type` in `lang`, newest first (paged by limit/offset)"
   (documents [_this type lang limit offset])
   ;; "Every published lineage's latest minor as decoded maps (sitemap + author hubs)"
   (published-latest [_this])
   ;; "Publish a whole change"
   (publish-change! [_this change-id])
   ;; "Returns languages of a tnr - expensive"
   (probe-tnr-languages [_this tnr])
   ;; "Event occur when tnlr is updated"
   (on-new-tnlr [_this tnlr]))
