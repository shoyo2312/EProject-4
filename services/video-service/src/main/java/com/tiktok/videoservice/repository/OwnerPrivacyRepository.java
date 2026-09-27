package com.tiktok.videoservice.repository;

import com.tiktok.videoservice.entity.OwnerPrivacy;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;

public interface OwnerPrivacyRepository extends MongoRepository<OwnerPrivacy, Long> {

    boolean existsByUserIdAndPrivateAccountTrue(Long userId);

    /** The private owners among {@code userIds}, in one query on _id. */
    List<OwnerPrivacy> findByUserIdInAndPrivateAccountTrue(Collection<Long> userIds);
}
