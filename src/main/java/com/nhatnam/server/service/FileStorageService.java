package com.nhatnam.server.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

public interface FileStorageService {
    String savePosProductImage(MultipartFile file) throws IOException;
    String saveCategoryImage(MultipartFile file) throws IOException;
    String saveProductImage(MultipartFile file) throws IOException;
    String saveVariantImage(MultipartFile file) throws IOException;
    String saveIngredientImage(MultipartFile file) throws IOException;
    void deleteFile(String filePath) throws IOException;
    byte[] getFile(String filePath) throws IOException;
    String saveSellerImportReceiptImage(MultipartFile file) throws IOException;
}