package com.hmdp.controller;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.service.IBlogService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 */
@RestController
@RequestMapping("/blog")
public class BlogController {

    @Resource
    private IBlogService blogService;

    @PostMapping
    public Result saveBlog(@RequestBody Blog blog) {
        return blogService.saveBlog(blog);
    }

    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable("id") Long id) {
        return blogService.likeBlog(id);
    }

    @GetMapping("/of/me")
    public Result queryMyBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", user.getId()).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    @GetMapping("/hot")
    public Result queryHotBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.queryHotBlog(current);
    }

    /** CityLens 使用的按店铺查询探店笔记接口。 */
    @GetMapping("/of/shop")
    public Result queryBlogByShop(@RequestParam("shopId") Long shopId,
                                  @RequestParam(value = "current", defaultValue = "1") Integer current) {
        if (shopId == null || shopId <= 0 || current < 1 || current > 100000) {
            return Result.fail("shopId 和 current 必须是正整数");
        }
        Page<Blog> page = blogService.query().eq("shop_id", shopId)
                .orderByDesc("create_time").page(new Page<>(current, 5));
        return Result.ok(page.getRecords(), page.getTotal());
    }

    /** CityLens 只读证据检索：限定店铺后按标题或正文关键词查笔记。 */
    @GetMapping("/search")
    public Result searchBlogEvidence(@RequestParam("shopId") Long shopId,
                                     @RequestParam("keyword") String keyword,
                                     @RequestParam(value = "current", defaultValue = "1") Integer current) {
        if (shopId == null || shopId <= 0 || current == null || current < 1 || current > 100000
                || keyword == null || keyword.trim().length() < 2 || keyword.trim().length() > 40) {
            return Result.fail("shopId、keyword 或 current 不合法");
        }
        String query = keyword.trim();
        Page<Blog> page = blogService.page(new Page<>(current, 5),
                new QueryWrapper<Blog>().eq("shop_id", shopId)
                        .and(w -> w.like("title", query).or().like("content", query))
                        .orderByDesc("create_time").orderByDesc("id"));
        return Result.ok(page.getRecords(), page.getTotal());
    }

    @GetMapping("/{id}")
    public Result queryBlogById(@PathVariable("id") Long id) {
        return blogService.queryBlogById(id);
    }

    @GetMapping("/likes/{id}")
    public Result queryBlogLikes(@PathVariable("id") Long id) {
        return blogService.queryBlogLikes(id);
    }

    @GetMapping("/of/user")
    public Result queryBlogByUserId(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam("id") Long id) {
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", id).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    @GetMapping("/of/follow")
    public Result queryBlogOfFollow(
            @RequestParam("lastId") Long max, @RequestParam(value = "offset", defaultValue = "0") Integer offset){
        return blogService.queryBlogOfFollow(max, offset);
    }
}
