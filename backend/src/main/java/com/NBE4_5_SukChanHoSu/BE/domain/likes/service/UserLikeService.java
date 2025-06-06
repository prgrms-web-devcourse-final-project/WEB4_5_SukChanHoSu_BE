package com.NBE4_5_SukChanHoSu.BE.domain.likes.service;

import com.NBE4_5_SukChanHoSu.BE.domain.admin.service.AdminMonitoringService;
import com.NBE4_5_SukChanHoSu.BE.domain.likes.dto.response.MatchingResponse;
import com.NBE4_5_SukChanHoSu.BE.domain.likes.dto.response.UserMatchingResponse;
import com.NBE4_5_SukChanHoSu.BE.domain.likes.entity.Matching;
import com.NBE4_5_SukChanHoSu.BE.domain.likes.entity.UserLikes;
import com.NBE4_5_SukChanHoSu.BE.domain.likes.repository.MatchingRepository;
import com.NBE4_5_SukChanHoSu.BE.domain.likes.repository.UserLikesRepository;
import com.NBE4_5_SukChanHoSu.BE.domain.recommend.service.CalculateDistance;
import com.NBE4_5_SukChanHoSu.BE.domain.recommend.service.RecommendService;
import com.NBE4_5_SukChanHoSu.BE.domain.user.dto.response.UserProfileResponse;
import com.NBE4_5_SukChanHoSu.BE.domain.user.entity.Gender;
import com.NBE4_5_SukChanHoSu.BE.domain.user.entity.UserProfile;
import com.NBE4_5_SukChanHoSu.BE.global.exception.redis.RedisSerializationException;
import com.NBE4_5_SukChanHoSu.BE.global.redis.config.RedisTTL;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.EntityManager;
import lombok.AllArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
@AllArgsConstructor
@Transactional(readOnly = true)
public class UserLikeService {

    private final UserLikesRepository userLikesRepository;
    private final MatchingRepository matchingRepository;
    private final EntityManager entityManager;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisTTL ttl;
    private final RecommendService matchingService;
    private final ObjectMapper objectMapper;
    private final CalculateDistance calculateDistance;

    private static final String LIKE_STREAM = "like";
    private static final String MATCHING_STREAM = "matching";
    private final AdminMonitoringService adminMonitoringService;

    @Transactional
    public UserLikes likeUser(UserProfile fromUser, UserProfile toUser) {
        // 좋아요 관계 생성
        UserLikes like = new UserLikes(fromUser, toUser);
        userLikesRepository.save(like);

        // Redis에 저장
        String key = "likes:" + fromUser.getUserId() + ":" + toUser.getUserId();
        redisTemplate.opsForValue().set(key, like, ttl.getLikes(), TimeUnit.SECONDS);

        // like 이벤트 발행 (메시지와 시간 분리)
        Map<String, String> likeEvent = new HashMap<>();
        likeEvent.put("toUserId", toUser.getUserId().toString());
        likeEvent.put("message", fromUser.getNickName() + "님이 like를 전송하였습니다!");
        likeEvent.put("time", like.getCreatedAt().toString());

        try {
            String jsonEvent = objectMapper.writeValueAsString(likeEvent); // Map -> JSON 직렬화
            redisTemplate.opsForStream().add(LIKE_STREAM, Collections.singletonMap("data", jsonEvent));
        } catch (Exception e) {
            throw new RedisSerializationException("500", "JSON 직렬화 실패");
        }

        // like 상태 업데이트
        String key2 = "user:" + fromUser.getUserId();
        // value값(movieCd) 보존 , 기본값: ""
        String existingValue = Optional.ofNullable((String) redisTemplate.opsForValue().get(key2)).orElse("");
        // like 전송시 레디스에 유저 정보 업데이트
        redisTemplate.opsForValue().set(key2, existingValue, ttl.getData(), TimeUnit.SECONDS);
        return like;
    }

    // to -> from 관계도 존재하는지 확인
    public boolean isAlreadyLiked(UserProfile fromUser, UserProfile toUser) {
        // Redis에서 "like" 상태 확인
        String key = "likes:" + toUser.getUserId() + ":" + fromUser.getUserId();

        // Redis에 "like" 상태가 있는지 확인
        if (Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
            return true;
        }


        return userLikesRepository.existsByFromUserAndToUser(toUser, fromUser);
    }

    // 이미 좋아요한 상황인지 검증
    public boolean isAlreadyLikes(UserProfile fromUser, UserProfile toUser) {
        // Redis에서 "like" 상태 확인
        String key = "likes:" + fromUser.getUserId() + ":" + toUser.getUserId();

        // Redis에 "like" 상태가 있는지 확인
        if (Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
            return true;
        }



        return userLikesRepository.existsByFromUserAndToUser(fromUser, toUser);
    }

    // 매칭 키 생성 메서드
    private String generateMatchingKey(Long maleId, Long femaleId) {
        return "matching:" + maleId + ":" + femaleId;
    }

    // like -> 매칭
    @Transactional
    public MatchingResponse matching(UserProfile fromUser, UserProfile toUser) {
        Matching matching;
        String key;
        // fromUser가 남자인 경우
        if (isMale(fromUser)) {
            matching = new Matching(fromUser, toUser);
            key = generateMatchingKey(fromUser.getUserId(), toUser.getUserId());
        }
        // fromUser가 여자인 경우(toUser가 남자인 경우)
        else {
            matching = new Matching(toUser, fromUser);
            key = generateMatchingKey(toUser.getUserId(), fromUser.getUserId());
        }
        // DB 저장
        matchingRepository.save(matching);
        // Redis 저장
        redisTemplate.opsForValue().set(key, matching, ttl.getMatching(), TimeUnit.SECONDS); // TTL 설정

        // 매칭 이벤트 발행
        Map<String, String> matchingEvent = new HashMap<>();

        matchingEvent.put("maleUserId", matching.getMaleUser().getUserId().toString()); // 남자 사용자 ID
        matchingEvent.put("femaleUserId", matching.getFemaleUser().getUserId().toString()); // 여자 사용자 ID
        matchingEvent.put("messageMale", matching.getFemaleUser().getNickName() + "님과 매칭되었습니다!"); // 남자에게 보낼 메시지
        matchingEvent.put("messageFemale", matching.getMaleUser().getNickName() + "님과 매칭되었습니다!"); // 여자에게 보낼 메시지
        matchingEvent.put("time", matching.getCreatedAt().toString());

        try {
            String jsonEvent = objectMapper.writeValueAsString(matchingEvent);
            redisTemplate.opsForStream().add(MATCHING_STREAM, Collections.singletonMap("data", jsonEvent));
        } catch (Exception e) {
            throw new RedisSerializationException("500", "JSON 직렬화 실패: "+e.getMessage());
        }

        // 일일 매칭 수 증가
        adminMonitoringService.incrementDailyMatches();
        // 좋아요 관계 삭제
        cancelLikes(fromUser, toUser);
        cancelLikes(toUser, fromUser);

        // 응답 생성
        int distance = calculateDistance.calDistance(fromUser, toUser);
        return new MatchingResponse(matching, distance);
    }

    // like 목록 조회
    public List<UserProfileResponse> getUserLikes(UserProfile user) {
        List<UserProfileResponse> likesUsers = new ArrayList<>();

        String pattern = "likes:" + user.getUserId() + ":*"; // likes:fromId:toId
        Set<String> keys = redisTemplate.keys(pattern);

        // 레디스에 값이 있는 경우
        if (!keys.isEmpty()) {
            for (String key : keys) {
                Object value = redisTemplate.opsForValue().get(key);
                if (value instanceof Map) {
                    Map<String, Object> map = (Map<String, Object>) value;  // Map(키-값 쌍)으로 캐스팅
                    try {
                        UserLikes like = objectMapper.convertValue(map, UserLikes.class); // Map -> UserLikes 클래스로 변환
                        int radius = calculateDistance.calDistance(like.getFromUser(), like.getToUser());  // 거리 계산
                        likesUsers.add(new UserProfileResponse(like.getToUser(), radius, like.getCreatedAt()));  // 메모리에 추가
                    } catch (IllegalArgumentException e) {
                        throw new RedisSerializationException("500", "JSON 역직렬화 실패");
                    }
                }
            }
        } else {
            for (UserLikes like : user.getLikes()) {  // 내가 좋아요한 사용자 목록
                UserProfile likedUser = like.getToUser();   // 좋아요를 받은 사용자
                if (likedUser != null) {
                    // 내가 좋아요 한 사용자 리스트에 추가
                    int radius = calculateDistance.calDistance(like.getFromUser(), like.getToUser());  // 거리 계산
                    likesUsers.add(new UserProfileResponse(likedUser, radius, like.getCreatedAt()));  // 메모리에 추가
                    // 캐싱
                    String key = "likes:" + user.getUserId() + ":" + likedUser.getUserId();
                    redisTemplate.opsForValue().set(key, like, ttl.getLikes(), TimeUnit.SECONDS);
                }
            }
        }

        // 정렬
        likesUsers.sort((r1,r2) -> r1.getCreatedAt().compareTo(r2.getCreatedAt()));
        return likesUsers;
    }

    // user의 liked 목록 조회
    public List<UserProfileResponse> getUserLiked(UserProfile user) {
        List<UserProfileResponse> likedUsers = new ArrayList<>();

        String pattern = "likes:*:" + user.getUserId(); // likes:fromId:toId
        Set<String> keys = redisTemplate.keys(pattern);

        // 레디스에 값이 있는 경우
        if (!keys.isEmpty()) {
            for (String key : keys) {
                System.out.println("###key: " + key);
                Object value = redisTemplate.opsForValue().get(key);
                if (value instanceof Map) {
                    Map<String, Object> map = (Map<String, Object>) value;
                    try {
                        UserLikes like = objectMapper.convertValue(map, UserLikes.class);
                        int distance = calculateDistance.calDistance(like.getFromUser(), like.getToUser());    // 거리 계산
                        likedUsers.add(new UserProfileResponse(like.getFromUser(), distance,like.getCreatedAt()));
                    } catch (IllegalArgumentException e) {
                        throw new RedisSerializationException("500", "JSON 역직렬화 실패");
                    }
                }
            }
        } else {
            for (UserLikes like : user.getLikedBy()) {    // 나를 좋아요한 사용자 목록
                UserProfile likesUser = like.getFromUser(); // 좋아요를 보낸 사용자
                if (likesUser != null) {
                    // 나를 좋아요한 사용자 리스트에 추가
                    int distance = calculateDistance.calDistance(user, likesUser);    // 거리 계산
                    likedUsers.add(new UserProfileResponse(likesUser, distance,like.getCreatedAt()));

                    // 캐싱
                    String key = "likes:" + likesUser.getUserId() + ":" + user.getUserId();
                    redisTemplate.opsForValue().set(key, like, ttl.getLikes(), TimeUnit.SECONDS);
                }
            }
        }

        // 정렬
        likedUsers.sort((r1,r2) -> r1.getCreatedAt().compareTo(r2.getCreatedAt()));
        return likedUsers;
    }

    // match 목록 조회
    public List<UserProfileResponse> getUserMatches(UserProfile user) {
        List<UserProfileResponse> responses = new ArrayList<>();
        String pattern;
        Set<String> keys;

        // 남자 유저
        if (isMale(user)) {
            pattern = "matching:" + user.getUserId() + ":*";  // matching:maleId:femaleId
            keys = redisTemplate.keys(pattern);

            // redis 검색
            if (!keys.isEmpty()) {
                for (String key : keys) {
                    Object value = redisTemplate.opsForValue().get(key);
                    if (value instanceof Map) {
                        Map<String, Object> map = (Map<String, Object>) value;
                        try {
                            Matching matching = objectMapper.convertValue(map, Matching.class);
                            System.out.println("추출한 유저: " + matching.getFemaleUser());
                            int distance = calculateDistance.calDistance(user, matching.getFemaleUser());
                            responses.add(new UserProfileResponse(matching.getFemaleUser(), distance, matching.getCreatedAt()));
                        } catch (IllegalArgumentException e) {
                            throw new RedisSerializationException("500", "JSON 역직렬화 실패"+e.getMessage());
                        }
                    }
                }
            } else {
                // DB 조회
                List<Matching> matches = matchingRepository.findByMaleUser(user);
                for (Matching matching : matches) {
                    // 매칭된 여자 유저 리스트에 등록
                    int distance = calculateDistance.calDistance(user, matching.getFemaleUser());
                    responses.add(new UserProfileResponse(matching.getFemaleUser(), distance, matching.getCreatedAt()));

                    // 캐싱
                    String key = "matching" + user.getUserId() + ":" + matching.getFemaleUser().getUserId();
                    redisTemplate.opsForValue().set(key, matching, ttl.getMatching(), TimeUnit.SECONDS);
                }
            }

        }
        // 여자 유저
        else {
            pattern = "matching:*:" + user.getUserId();     // matching:maleId:femaleId
            keys = redisTemplate.keys(pattern);

            if (!keys.isEmpty()) {
                for (String key : keys) {
                    Object value = redisTemplate.opsForValue().get(key);
                    if (value instanceof Map) {
                        Map<String, Object> map = (Map<String, Object>) value;
                        try {
                            Matching matching = objectMapper.convertValue(map, Matching.class);
                            int distance = calculateDistance.calDistance(user, matching.getMaleUser());
                            responses.add(new UserProfileResponse(matching.getMaleUser(), distance, matching.getCreatedAt()));
                            System.out.println("추출한 유저: " + matching.getMaleUser());
                        } catch (IllegalArgumentException e) {
                            throw new RedisSerializationException("500", "JSON 역직렬화 실패");
                        }
                    }
                }
            } else {
                // DB 조회
                List<Matching> matches = matchingRepository.findByFemaleUser(user);
                for (Matching matching : matches) {
                    // 매칭된 남자 유저 리스트에 등록
                    int distance = calculateDistance.calDistance(user, matching.getMaleUser());
                    responses.add(new UserProfileResponse(matching.getMaleUser(), distance, matching.getCreatedAt()));

                    // 캐싱
                    String redisKey = "matching:" + matching.getMaleUser().getUserId() + ":" + user.getUserId();
                    redisTemplate.opsForValue().set(redisKey, matching, ttl.getMatching(), TimeUnit.SECONDS);
                }
            }
        }

        responses.sort((r1,r2) -> r1.getCreatedAt().compareTo(r2.getCreatedAt()));
        return responses;
    }

    // 남자인지 검증 (남자:true,여자:false)
    public boolean isMale(UserProfile user) {
        return user.getGender().equals(Gender.Male);
    }

    // 매칭테이블에 이미 있는지 검증
    public boolean isAlreadyMatched(UserProfile fromUser, UserProfile toUser) {
        String key;
        if (isMale(fromUser)) {
            key = "matching:" + fromUser.getUserId() + ":" + toUser.getUserId();
        } else {
            key = "matching:" + toUser.getUserId() + ":" + fromUser.getUserId();
        }

        // Redis에 매칭 상태가 있는지 확인
        if (Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
            return true;
        }

        if(isMale(fromUser)){
            return matchingRepository.existsByMaleUserAndFemaleUser(fromUser,toUser);
        }else{
            return matchingRepository.existsByMaleUserAndFemaleUser(toUser,fromUser);
        }
    }

    @Transactional
    public void cancelLikes(UserProfile fromUser, UserProfile toUser) {
        userLikesRepository.deleteByFromUserAndToUser(fromUser, toUser);
        /*
         DB 반영
         삭제 연산 수행 후, 남아있는 엔티티 정보를 제거
         detach 상태로 변경 -> DB에서 새로운 데이터를 가져오도록 보장
         */
        entityManager.flush();
        entityManager.clear();

        // Redis에서 삭제
        String key = "likes:" + fromUser.getUserId() + ":" + toUser.getUserId();
        redisTemplate.delete(key);
    }

    @Transactional
    public void cancelMatch(UserProfile fromUser, UserProfile toUser) {
        if (isMale(fromUser)) {
            matchingRepository.deleteByMaleUserAndFemaleUser(fromUser, toUser);
            // Redis에서 삭제
            String key = generateMatchingKey(fromUser.getUserId(), toUser.getUserId());
            redisTemplate.delete(key);
        } else {
            matchingRepository.deleteByMaleUserAndFemaleUser(toUser, fromUser);
            // Redis에서 삭제
            String key = generateMatchingKey(toUser.getUserId(), fromUser.getUserId());
            redisTemplate.delete(key);
        }
    }

    public boolean isSameGender(UserProfile fromUser, UserProfile toUser) {
        return fromUser.getGender().equals(toUser.getGender());
    }
}
