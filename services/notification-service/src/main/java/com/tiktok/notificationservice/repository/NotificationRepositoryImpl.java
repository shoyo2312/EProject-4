package com.tiktok.notificationservice.repository;

import com.tiktok.notificationservice.entity.Notification;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.List;

import static org.springframework.data.mongodb.core.query.Criteria.where;

@RequiredArgsConstructor
public class NotificationRepositoryImpl implements NotificationRepositoryCustom {

    private final MongoTemplate mongoTemplate;

    @Override
    public List<Notification> findPage(Long recipientId, Notification after, int size) {
        Criteria criteria = where("recipientId").is(recipientId);
        if (after != null) {
            criteria = criteria.orOperator(
                    where("createdAt").lt(after.getCreatedAt()),
                    where("createdAt").is(after.getCreatedAt()).and("_id").lt(after.getId()));
        }
        Query query = Query.query(criteria)
                .with(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("_id")))
                .limit(size);
        return mongoTemplate.find(query, Notification.class);
    }

    @Override
    public long markAllRead(Long recipientId) {
        return mongoTemplate.updateMulti(
                Query.query(where("recipientId").is(recipientId).and("read").is(false)),
                Update.update("read", true),
                Notification.class).getModifiedCount();
    }
}
