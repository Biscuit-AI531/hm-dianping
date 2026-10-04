package com.hmdp.dto;

import lombok.Data;

@Data
public class ShopSearchDTO {
    private Long typeId;
    private String keyword;
    private String area;
    private Long maxPrice;
    /** 点评数据库中的评分，4.5 分传 45。 */
    private Integer minScore;
    /** 只返回当前有可用优惠券的店铺。 */
    private Boolean hasVoucher;
    private Double x;
    private Double y;
    private Integer page = 1;
    private Integer size = 10;
    /** score、price 或 distance。 */
    private String sort = "score";
}
