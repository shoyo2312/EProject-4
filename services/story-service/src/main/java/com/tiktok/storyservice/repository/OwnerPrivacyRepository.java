package com.tiktok.storyservice.repository;

import com.tiktok.storyservice.entity.OwnerPrivacy;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;

public interface OwnerPrivacyRepository extends MongoRepository<OwnerPrivacy, Long> {

    /** The private owners among {@code userIds}, in one query on _id. */
    List<OwnerPrivacy> findByUserIdInAndPrivateAccountTrue(Collection<Long> userIds);
}
