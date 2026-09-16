package com.example.paperassistant.controller;

import com.example.paperassistant.common.response.ApiResponse;
import com.example.paperassistant.model.dto.LibraryCreateDTO;
import com.example.paperassistant.model.dto.LibraryDTO;
import com.example.paperassistant.model.dto.LibraryUpdateDTO;
import com.example.paperassistant.model.vo.LibraryVO;
import com.example.paperassistant.service.LibraryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 论文库 HTTP 接口：负责参数校验、调用业务服务、组织响应，不写 SQL。 */
@RestController
@RequestMapping("/api/libraries")
@Profile("postgres")
public class LibraryController {

    private final LibraryService libraryService;

    public LibraryController(LibraryService libraryService) {
        this.libraryService = libraryService;
    }

    /** 创建论文库，返回 HTTP 201 和数据库生成的记录。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<LibraryVO> createLibrary(@Valid @RequestBody LibraryCreateDTO request) {
        return ApiResponse.success(toVO(libraryService.createLibrary(request)));
    }

    /** 查询论文库；初版用于本机少量数据，分页和用户隔离在后续实现。 */
    @GetMapping
    public ApiResponse<List<LibraryVO>> listLibraries() {
        List<LibraryVO> libraries = libraryService.listLibraries().stream()
                .map(this::toVO)
                .toList();
        return ApiResponse.success(libraries);
    }

    /** 完整替换可编辑字段，保持创建时间并更新修改时间。 */
    @PutMapping("/{id}")
    public ApiResponse<LibraryVO> updateLibrary(
            @PathVariable @Positive(message = "论文库 ID 必须为正整数") long id,
            @Valid @RequestBody LibraryUpdateDTO request) {
        return ApiResponse.success(toVO(libraryService.updateLibrary(id, request)));
    }

    /** 删除当前未关联论文的库；成功响应无消息体。 */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteLibrary(@PathVariable @Positive(message = "论文库 ID 必须为正整数") long id) {
        libraryService.deleteLibrary(id);
    }

    private LibraryVO toVO(LibraryDTO library) {
        return new LibraryVO(library.id(), library.name(), library.description(),
                library.gmtCreate(), library.gmtModified());
    }
}
