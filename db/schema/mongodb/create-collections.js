// ---- chat_db ----
db = db.getSiblingDB("chat_db");
db.createCollection("chat_user_prefs");
db.createCollection("conversations");
db.getCollection("conversations").createIndex({"participantKey":1}, {"name":"participant_key_idx","unique":true});
db.getCollection("conversations").createIndex({"participantIds":1}, {"name":"participantIds"});
db.createCollection("messages");
db.getCollection("messages").createIndex({"conversationId":1,"sentAt":-1,"_id":-1}, {"name":"conversation_sent_id_idx"});
db.getCollection("messages").createIndex({"conversationId":1}, {"name":"one_request_message_idx","unique":true,"partialFilterExpression":{"requestMessage":true}});
db.getCollection("messages").createIndex({"conversationId":1,"senderId":1,"clientRequestId":1}, {"name":"message_client_request_idx","unique":true,"partialFilterExpression":{"clientRequestId":{"$exists":true}}});

// ---- notification_db ----
db = db.getSiblingDB("notification_db");
db.createCollection("device_tokens");
db.getCollection("device_tokens").createIndex({"userId":1}, {"name":"userId"});
db.createCollection("notifications");
db.getCollection("notifications").createIndex({"recipientId":1,"createdAt":-1,"_id":-1}, {"name":"recipient_page_idx"});
db.getCollection("notifications").createIndex({"recipientId":1,"read":1}, {"name":"recipient_unread_idx"});
db.getCollection("notifications").createIndex({"recipientId":1,"actorId":1,"type":1,"referenceId":1,"createdAt":-1}, {"name":"collapse_idx"});
db.createCollection("processed_events");
db.getCollection("processed_events").createIndex({"eventId":1}, {"name":"eventId","unique":true});
db.getCollection("processed_events").createIndex({"processedAt":1}, {"name":"processedAt","expireAfterSeconds":2592000});

// ---- story_db ----
db = db.getSiblingDB("story_db");
db.createCollection("owner_bans");
db.getCollection("owner_bans").createIndex({"bannedUntil":1}, {"name":"bannedUntil"});
db.getCollection("owner_bans").createIndex({"deactivatedAt":1}, {"name":"deactivatedAt","sparse":true});
db.createCollection("stories");
db.getCollection("stories").createIndex({"userId":1,"status":1,"expiresAt":1}, {"name":"author_status_idx"});
db.getCollection("stories").createIndex({"userId":1}, {"name":"userId"});
db.getCollection("stories").createIndex({"mediaKey":1}, {"name":"mediaKey","unique":true});
db.getCollection("stories").createIndex({"uploadKey":1}, {"name":"uploadKey","unique":true,"sparse":true});
db.getCollection("stories").createIndex({"expiresAt":1}, {"name":"expiresAt","expireAfterSeconds":0});
db.createCollection("story_views");
db.getCollection("story_views").createIndex({"storyId":1,"viewerId":1}, {"name":"story_viewer_idx","unique":true});
db.getCollection("story_views").createIndex({"viewerId":1,"storyId":1}, {"name":"viewer_story_idx"});
db.getCollection("story_views").createIndex({"storyId":1,"viewedAt":-1}, {"name":"story_viewed_idx"});
db.getCollection("story_views").createIndex({"expiresAt":1}, {"name":"expiresAt","expireAfterSeconds":0});

// ---- video_db ----
db = db.getSiblingDB("video_db");
db.createCollection("owner_bans");
db.getCollection("owner_bans").createIndex({"bannedUntil":1}, {"name":"bannedUntil"});
db.getCollection("owner_bans").createIndex({"deactivatedAt":1}, {"name":"deactivatedAt","sparse":true});
db.createCollection("owner_privacy");
db.createCollection("processed_events");
db.getCollection("processed_events").createIndex({"eventId":1}, {"name":"eventId","unique":true});
db.getCollection("processed_events").createIndex({"processedAt":1}, {"name":"processedAt","expireAfterSeconds":2592000});
db.createCollection("videos");
db.getCollection("videos").createIndex({"status":1,"visibility":1,"deletedAt":1,"createdAt":-1,"_id":-1}, {"name":"feed_idx"});
db.getCollection("videos").createIndex({"userId":1,"deletedAt":1,"status":1,"visibility":1,"createdAt":-1}, {"name":"user_videos_idx"});
db.getCollection("videos").createIndex({"eventPublishedAt":1,"eventFailedAt":1,"deletedAt":1,"createdAt":1}, {"name":"outbox_idx"});
db.getCollection("videos").createIndex({"deleteEventPublishedAt":1,"deletedAt":1}, {"name":"delete_outbox_idx"});
db.getCollection("videos").createIndex({"purgeEventPublishedAt":1,"deletedAt":1}, {"name":"purge_outbox_idx"});
db.getCollection("videos").createIndex({"visibilityEventPendingAt":1}, {"name":"visibility_outbox_idx"});
db.getCollection("videos").createIndex({"purgeRequestedAt":1}, {"name":"purge_request_idx"});
db.getCollection("videos").createIndex({"rawFileUrl":1}, {"name":"raw_file_idx","unique":true});
db.getCollection("videos").createIndex({"userId":1}, {"name":"userId"});
db.getCollection("videos").createIndex({"tags":1}, {"name":"tags"});

