package com.lhj.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.lhj.constant.GlobalConstants;
import common.ErrorCode;
import common.util.AlgorithmUtils;
import common.util.SafetyUserUtils;
import com.lhj.exception.BusinessException;
import com.lhj.service.UserService;
import com.lhj.mapper.UserMapper;

import domain.Team;
import domain.User;
import domain.dto.UserQuery;
import domain.requestBody.UserLoginRequest;
import domain.requestBody.UserRegisterRequest;
import domain.vo.TeamUserVO;
import domain.vo.UserVO;
import enums.TeamStatusEnum;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.DigestUtils;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
* @author Li
* @description 针对表【user(用户)】的数据库操作Service实现
* @createDate 2023-06-07 15:15:16
*/
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User>
    implements UserService{
    @Resource
    private UserMapper userMapper;
    @Resource
    private RedisTemplate<String,Object> redisTemplate;

    private static final String CHAPASS="lihua";
    private static final String USER_LOGIN="useLonginState";


    @Override
    public Long userRegister(UserRegisterRequest userRegisterRequest) {
        String userAccount=userRegisterRequest.getUserAccount();
        String userPassword=userRegisterRequest.getUserPassword();
        String checkPassword = userRegisterRequest.getCheckPassword();
        String planetCode = userRegisterRequest.getPlanetCode();
        if(StringUtils.isBlank(userAccount))throw new BusinessException(ErrorCode.PARAMS_ERROR,"帐号格式错误");
        if(userAccount.length()<4 ||userPassword.length()<8){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"帐号或密码长度错误");
        }

        //帐户不能包括特殊字符
        String regEx = "[ _`~!@#$%^&*()+=|{}':;',\\[\\].<>/?~！@#￥%……&*（）——+|{}【】‘；：”“’。，、？]|\n|\r|\t";
        Pattern p = Pattern.compile(regEx);
        Matcher m = p.matcher(userAccount);
        if(m.find()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"帐号包含特殊字符");
        }

        if(!userPassword.equals(checkPassword)){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"两次输入密码不一致");
        }

        QueryWrapper<User> query=new QueryWrapper<>();
        query.eq("userAccount",userAccount);
        long count =userMapper.selectCount(query);
        if(count>0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"帐户已存在");
        }

        //密码加密
        String changePassword= DigestUtils.md5DigestAsHex((CHAPASS+userPassword).getBytes());
        User user = new User();
        user.setUserAccount(userAccount);
        user.setUserPassword(changePassword);
        user.setPlanetCode(planetCode);
        int insert = userMapper.insert(user);
        if(insert<=0){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,"数据库插入失败");
        }
        return user.getId();
    }

    /**
     * 用户登录功能实现,校验用户再查询
     * @param userLoginRequest  登录验证参数
     * @return
     */
    @Override
    public User userLongin(UserLoginRequest userLoginRequest, HttpServletRequest request) {
        String userAccount =userLoginRequest.getUserAccount();
        String password=userLoginRequest.getUserPassword();
        String sessionId = userLoginRequest.getSessionId();
        if(StringUtils.isBlank(userAccount)){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"帐号不能为空");
        }
        if(userAccount.length()<4 || password.length()<8)throw new BusinessException(ErrorCode.PARAMS_ERROR,"帐号长度不符");

        //帐户不能包括特殊字符
        //这是包括特列字符的正则表达式
        String regEx = "[ _`~!@#$%^&*()+=|{}':;',\\[\\].<>/?~！@#￥%……&*（）——+|{}【】‘；：”“’。，、？]|\n|\r|\t";
        Pattern p = Pattern.compile(regEx);
        Matcher m = p.matcher(userAccount);
        if(m.find())throw new BusinessException(ErrorCode.PARAMS_ERROR,"帐号包含特殊字符");

        QueryWrapper<User> queryWrapper=new QueryWrapper<>();
        queryWrapper.eq("userAccount",userAccount);
        String changePassword= DigestUtils.md5DigestAsHex((CHAPASS+password).getBytes());
        queryWrapper.eq("userPassword",changePassword);
        User user = userMapper.selectOne(queryWrapper);
        if(user==null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"帐号不存在");
        }
        User safetyUser = SafetyUserUtils.toSafeTyUser(user);
        if(StringUtils.isNotBlank(sessionId)) {
            redisTemplate.opsForValue().set(sessionId,safetyUser);
            redisTemplate.expire(sessionId,10, TimeUnit.MINUTES);
        }
        request.getSession().setAttribute(USER_LOGIN,safetyUser);
        return safetyUser;
    }

    @Override
    public List<User> searchByCondition(UserQuery conditionUser) {
        QueryWrapper<User> query=new QueryWrapper<>();
        String username = conditionUser.getUsername();
        if(StringUtils.isNotBlank(username)){
            query.like("username",username);
        }
        Long id = conditionUser.getId();
        if(id!=null && id>0)query.eq("id",id);
        List<Long> idList = conditionUser.getIdList();
        if(idList!=null){
            query.in("id",idList);
        }
        Integer gender = conditionUser.getGender();
        if(gender!=null){
            query.eq("gender",gender);
        }
        String userAccount = conditionUser.getUserAccount();
        if(StringUtils.isNotBlank(userAccount)){
            query.eq("userAccount",userAccount);
        }
        String searchText = conditionUser.getSearchText();
        if (StringUtils.isNotBlank(searchText)) {
            query.and(qw -> qw.like("username", searchText).or().like("tags", searchText));
        }
        List<User> userList=this.list(query);
        userList=Optional.ofNullable(userList).orElse(new ArrayList<>());
        List<String> tagsName = conditionUser.getTagsName();
        if(tagsName!=null){
            return userList.stream().filter(user -> {
                String tags = user.getTags();
                Gson gson=new Gson();
                List<String> tagsList = gson.fromJson(tags, new TypeToken<List<String>>() {
                }.getType());
                for (String s : tagsName) {
                    if(!tagsList.contains(s))return false;
                }
                return true;
            }).collect(Collectors.toList());
        }
        return userList;
    }

    @Override
    public Boolean deleteByIds(int[] ids) {
        int rows = userMapper.deleteByIds(ids);
        if(rows<=0) return false;
        return true;
    }

    /**
     * 通过内存过滤sql
     * @param tagNames  要查询出的用户所必须具有的标签名
     * @return
     */
    @Override
    public List<User> searchByTags(List<String> tagNames) {
        if(CollectionUtils.isEmpty(tagNames))throw new BusinessException(ErrorCode.PARAMS_ERROR);
        QueryWrapper queryWrapper=new QueryWrapper();
        queryWrapper.isNotNull("tags");
        queryWrapper.ne("tags","[]");
        List<User> users = userMapper.selectList(queryWrapper);
        Gson gson= new Gson();
        List<User> tagUsers = users.stream().filter(user -> {
            if(StringUtils.isBlank(user.getTags()))return false;
            String tagsStr = user.getTags();
            List<String> tagsList = gson.fromJson(tagsStr, new TypeToken<List<String>>() {
            }.getType());
            //判断是否集合为空，若为空，则新创建一个集合对象
            tagsList= Optional.ofNullable(tagsList).orElse(new ArrayList<>());
            for (String tagName : tagNames) {
                if(!tagsList.contains(tagName))return false;
            }
            return true;
        }).map(SafetyUserUtils::toSafeTyUser).collect(Collectors.toList());
        return tagUsers;
    }

    /**
     * 取到登录的用户信息
     * @param request
     * @return
     */
    @Override
    public User getLoginUser(String sessionId,HttpServletRequest request) {
        if(sessionId==null) return null;
        Object loginUser=redisTemplate.opsForValue().get(sessionId);
        if(loginUser==null) loginUser = request.getSession().getAttribute(USER_LOGIN);
        if(loginUser==null) throw new BusinessException(ErrorCode.NOT_LOGIN);
        return (User) loginUser;
    }


    /**
     * 是否是管理员
     * @param user
     * @return
     */
    @Override
    public Boolean isAdmin(User user) {
        if(user==null )throw new BusinessException(ErrorCode.PARAMS_ERROR);
        if(user.getUserRole()==1)return true;
        return false;
    }

    /**
     * 修改用户信息
     * @param user
     * @param loginUser
     * @return
     */
    @Override
    public Integer updateUser(User user, User loginUser) {
        if (user == null || loginUser == null) throw new BusinessException(ErrorCode.PARAMS_ERROR);
        Long userId = user.getId();
        //如果是不是管理员，则只能修改自己的信息
        boolean ofMe=userId.equals(loginUser.getId());
        if (!isAdmin(loginUser) && !ofMe ) {
            throw new BusinessException(ErrorCode.NO_AUTH);
        }
        return userMapper.updateById(user);

    }

    @Override
    public List<UserVO> matchUsers(Integer num, User loginUser) {
        if(loginUser==null)throw new BusinessException(ErrorCode.NOT_LOGIN);
        Long loginId = loginUser.getId();
        loginUser = this.getById(loginId);
        //读缓存
        ValueOperations<String, Object> opsForValue = redisTemplate.opsForValue();
        List<UserVO> cacheResult = (List<UserVO>)opsForValue.get(GlobalConstants.matchLock + loginId);
        if(cacheResult!=null){
            cacheResult = cacheResult.stream().limit(num).collect(Collectors.toList());
            return cacheResult;
        }
        String tags = loginUser.getTags();
        Gson gson = new Gson();
        List<String> loginTags = gson.fromJson(tags, new TypeToken<List<String>>() {
        }.getType());
        QueryWrapper<User> query=new QueryWrapper();
        query.isNotNull("tags");
        query.select("id","tags");
        List<User> users = this.list(query);
        List<Pair<User,Long>> pairList=new ArrayList<>();
        for(int i=0;i<users.size();i++){
            User user = users.get(i);
            String userTag = user.getTags();
            if(StringUtils.isBlank(userTag) || user.getId().equals(loginId)){
                continue;
            }
            List<String> userTags = gson.fromJson(userTag, new TypeToken<List<String>>() {
            }.getType());
            long minDistance = AlgorithmUtils.minDistance(loginTags, userTags);
            pairList.add(Pair.of(user,minDistance));
        }
        pairList.sort((a,b)->{
            return (int)(a.getValue()-b.getValue());
        });
        List<UserVO> result = pairList.stream().map(pair -> {
            User u = this.getById(pair.getKey().getId());
            UserVO userVO = new UserVO();
            BeanUtils.copyProperties(u,userVO);
            return userVO;
        }).limit(10).collect(Collectors.toList());
        //写缓存
        opsForValue.set(GlobalConstants.matchLock+loginId,result,60, TimeUnit.SECONDS);
        return result;
    }


}




