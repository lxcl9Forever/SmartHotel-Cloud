package com.smarthotel.controller;
import com.smarthotel.common.result.Result;
import com.smarthotel.mapper.AdminPaymentQueryMapper;
import com.smarthotel.vo.AdminPaymentVO;
import com.smarthotel.vo.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/admin/payments") @RequiredArgsConstructor
public class AdminPaymentController {
 private final AdminPaymentQueryMapper mapper;
 @GetMapping public Result<PageResult<AdminPaymentVO>> payments(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="10") int pageSize) {
  int p=Math.max(1,page), size=Math.max(1,Math.min(pageSize,100));
  return Result.success(new PageResult<>(mapper.countAll(),p,size,mapper.list((long)(p-1)*size,size)));
 }
}
